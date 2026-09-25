package com.weddingplanner.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.weddingplanner.crm.domain.BudgetBand;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads a first message the way a planner would before answering it: who is writing, what the
 * wedding is worth, and whether it is waiting on us.
 *
 * <p>The questions go to TypeSafe's Jev model as typed questions — a Choice for the sender, a
 * Choice over the app's own {@link BudgetBand} values, a Noul for urgency — so each answer lands on
 * a value the app already stores, with a probability the inbox can show. Amounts are found by a
 * regex and Jev only picks which of them is the budget: the number stored is always one the couple
 * wrote, never one a model produced.</p>
 *
 * <p>Without {@code planner.jev.api-key}, or when the call fails, a keyword reading answers the same
 * questions so a demo never stalls on the network. The result says which of the two it was.</p>
 */
@Service
public class JevClassifier {

    private static final Logger log = LoggerFactory.getLogger(JevClassifier.class);
    private static final URI ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");

    /** Who can write to a wedding planner's inbox. Keys are what the router switches on. */
    public static final Map<String, String> SENDERS = linked(
            "couple", "A couple or their family planning their own wedding",
            "vendor", "A supplier pitching services: florist, caterer, photographer, band, rentals",
            "venue", "A venue, villa or hotel offering to host weddings",
            "press_partner", "Press, an influencer, or a partnership / collaboration request",
            "spam", "Spam, marketing, SEO offers or anything unrelated to planning a wedding");

    private static final Map<String, String> BANDS = linked(
            BudgetBand.BELOW_300K.name(), "A total wedding budget under €300,000",
            BudgetBand.FROM_300K_TO_499K.name(), "A total wedding budget from €300,000 to €499,999",
            BudgetBand.FROM_500K_TO_749K.name(), "A total wedding budget from €500,000 to €749,999",
            BudgetBand.FROM_750K.name(), "A total wedding budget of €750,000 or more",
            BudgetBand.UNKNOWN.name(), "No budget is stated or implied");

    /** €600k, € 1.2m, 450,000 euros, 300k EUR — digits the sender typed next to a currency. */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:€\\s?(\\d[\\d.,]*)\\s?(k|m|million|thousand)?\\b)"
                    + "|(?:\\b(\\d[\\d.,]*)\\s?(k|m|million|thousand)?\\s?(?:€|eur\\b|euros?\\b))");
    private static final Pattern GUESTS = Pattern.compile("(?i)\\b(\\d{2,4})\\s*(?:guests|people|persons|pax)\\b");

    public record Answer(String choice, double probability) {}

    /** Everything the router and the chat card need. {@code budget} is null when none was written. */
    public record Result(String engine, String model, long millis,
                         Answer sender, double senderConfidence,
                         Answer band, double urgency,
                         BigDecimal budget, String budgetSpan, Integer guests) {
        public boolean live() { return "jev".equals(engine); }
    }

    private final ObjectMapper json;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public JevClassifier(ObjectMapper json,
                         @Value("${planner.jev.api-key:}") String apiKey,
                         @Value("${planner.jev.model:jev-latest}") String model) {
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
    }

    public Result classify(String channel, String sender, String message) {
        var amounts = amounts(message);
        Integer guests = guests(message);
        if (!apiKey.isEmpty()) {
            try {
                return ask(channel, sender, message, amounts, guests);
            } catch (Exception e) {
                log.warn("Jev classification failed, falling back to keywords: {}", e.toString());
            }
        }
        return keywords(message, amounts, guests);
    }

    private Result ask(String channel, String sender, String message, List<Amount> amounts, Integer guests)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        ObjectNode state = body.putObject("state");
        state.put("channel", channel);
        state.put("from", sender);
        state.put("message", message);
        body.put("model", model);
        ObjectNode questions = body.putObject("questions");
        choice(questions, "sender_type", "Who is writing to this luxury wedding planner?", SENDERS);
        choice(questions, "budget_band", "Which total wedding budget does the message state or imply?", BANDS);
        questions.putObject("urgency")
                .put("type", "noul")
                .put("instructions", "Does the sender need an answer soon — a wedding within six months, "
                        + "a question about availability, or explicit time pressure?");
        if (!amounts.isEmpty()) {
            var options = new LinkedHashMap<String, String>();
            for (int i = 0; i < amounts.size(); i++) options.put("amount_" + i, "\"" + amounts.get(i).span() + "\"");
            options.put("none", "None of these amounts is the total wedding budget");
            choice(questions, "budget_amount", "Which amount quoted in the message is the total wedding budget?", options);
        }

        long started = System.nanoTime();
        var request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (response.statusCode() != 200)
            throw new IllegalStateException("TypeSafe answered HTTP " + response.statusCode() + ": " + response.body());

        JsonNode reply = json.readTree(response.body());
        JsonNode answers = reply.path("answers");
        Answer senderType = picked(answers.path("sender_type"));
        Answer band = picked(answers.path("budget_band"));
        double urgency = answers.path("urgency").path("noul").asDouble(0);

        Amount budget = null;
        if (!amounts.isEmpty()) {
            String which = answers.path("budget_amount").path("choice").asText("none");
            if (which.startsWith("amount_")) budget = amounts.get(Integer.parseInt(which.substring(7)));
        }
        return new Result("jev", reply.path("model").asText(model), millis,
                senderType, answers.path("sender_type").path("confidence").asDouble(0),
                band, urgency,
                budget == null ? null : budget.value(), budget == null ? null : budget.span(), guests);
    }

    /** The stand-in: plain keyword rules, honest about being one. */
    private Result keywords(String message, List<Amount> amounts, Integer guests) {
        String text = message.toLowerCase(Locale.ROOT);
        String sender = has(text, "seo", "backlink", "crypto", "unsubscribe", "ranking", "click here") ? "spam"
                : has(text, "magazine", "editor", "feature", "collab", "influencer", "press") ? "press_partner"
                : has(text, "our venue", "our villa", "our estate", "our hotel", "host your") ? "venue"
                : has(text, "portfolio", "price sheet", "price list", "preferred", "supplier", "commission",
                        "our studio", "vendor list") ? "vendor"
                : has(text, "wedding", "married", "fiancé", "fiance", "partner and i", "our day") ? "couple"
                : null;
        Amount budget = amounts.stream().max((a, b) -> a.value().compareTo(b.value())).orElse(null);
        BudgetBand band = budget == null ? BudgetBand.UNKNOWN : com.weddingplanner.crm.domain.LeadInquiry.classifyBudget(budget.value());
        double urgency = has(text, "asap", "urgent", "availability", "available", "next month", "this summer") ? 0.8 : 0.2;
        return new Result("keywords", "keyword rules", 0,
                new Answer(sender == null ? "couple" : sender, sender == null ? 0.4 : 0.9), sender == null ? 0.4 : 0.9,
                new Answer(band.name(), budget == null ? 0.5 : 0.9), urgency,
                budget == null ? null : budget.value(), budget == null ? null : budget.span(), guests);
    }

    private record Amount(String span, BigDecimal value) {}

    static List<Amount> amounts(String message) {
        var found = new ArrayList<Amount>();
        Matcher m = AMOUNT.matcher(message);
        while (m.find()) {
            String digits = m.group(1) != null ? m.group(1) : m.group(3);
            String unit = m.group(1) != null ? m.group(2) : m.group(4);
            BigDecimal value = number(digits, unit);
            // Anything under €10k is a deposit, a fee or a per-head price, not a wedding.
            if (value != null && value.compareTo(new BigDecimal("10000")) >= 0 && found.size() < 8)
                found.add(new Amount(m.group().strip(), value));
        }
        return found;
    }

    private static BigDecimal number(String digits, String unit) {
        if (digits == null) return null;
        String clean = digits.replaceAll("[.,](?=\\d{3}\\b)", "").replace(',', '.');
        try {
            BigDecimal value = new BigDecimal(clean.replaceAll("[.,]$", ""));
            if (unit == null) return value;
            return switch (unit.toLowerCase(Locale.ROOT)) {
                case "k", "thousand" -> value.multiply(BigDecimal.valueOf(1_000));
                case "m", "million" -> value.multiply(BigDecimal.valueOf(1_000_000));
                default -> value;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer guests(String message) {
        Matcher m = GUESTS.matcher(message);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static void choice(ObjectNode questions, String id, String instructions, Map<String, String> options) {
        ObjectNode q = questions.putObject(id);
        q.put("type", "choice");
        q.put("instructions", instructions);
        ObjectNode criteria = q.putObject("criteria");
        options.forEach(criteria::put);
    }

    private static Answer picked(JsonNode answer) {
        String choice = answer.path("choice").asText(null);
        double p = choice == null ? 0 : answer.path("probabilities").path(choice).asDouble(0);
        return new Answer(choice, p);
    }

    private static boolean has(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static Map<String, String> linked(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }
}
