package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.EventBudgetRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * The book of weddings Wedding Planner is actually working on.
 *
 * <p>An event is a lead that said yes, so the book is built from the booked inquiries rather than
 * invented beside them: the couple's own name heads the event, their stated date, place, guest
 * count and budget carry over, and the contact card the inquiry already created is its client. A
 * demo whose Events list and whose pipeline disagree about who booked is worse than no demo.</p>
 *
 * <p>Each event gets an estimate of its own, built from an imported example and re-priced to that
 * couple's budget, because the event page is a page about money: without a scenario there is
 * nothing to break down, commit or export. Those numbers are demo data and say so — only the two
 * imported PDF examples carry amounts anybody actually quoted.</p>
 */
// After EventExampleSeeder(20) supplies the estimate templates, before EventCrewSeeder(30) staffs
// what this creates.
@Order(25)
@ConditionalOnProperty(name = "planner.events.demo", havingValue = "true")
@Component
public class EventBookSeeder implements CommandLineRunner {

    /**
     * Fictional venues, one set per region the demand seeder sends couples to. They are invented on
     * purpose: the two imported examples are the only place a real venue's real prices appear.
     */
    private static final Map<String, String[]> VENUES = Map.of(
            "Lake Como", new String[]{"Villa Serbelloni", "Villa Aurelia", "Villa Fontanella"},
            "Tuscany", new String[]{"Borgo San Marco", "Castello di Selvena", "Tenuta Le Fonti"},
            "Amalfi Coast", new String[]{"Villa Marinella", "Convento di Pietro", "Terrazza Solaro"},
            "Lake Maggiore", new String[]{"Villa Isolotto", "Palazzo Verbano"},
            "Puglia", new String[]{"Masseria Lucente", "Masseria Santa Chiara"},
            "Sicily", new String[]{"Dimora Aurora", "Baglio della Rosa"},
            "Portofino", new String[]{"Castello Levante", "Villa Paraggi"},
            "Venice", new String[]{"Palazzo Contarini", "Ca' Loredana"},
            "Capri", new String[]{"Villa Tiberio", "Torre Saracena"},
            "Umbria", new String[]{"Castello di Reschia", "Borgo dei Tigli"});
    private static final String[] FALLBACK_VENUES = {"Villa Aurelia", "Borgo San Marco", "Masseria Lucente"};

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final LeadInquiryRepository leads;
    private final ContactRepository contacts;
    private final com.weddingplanner.crm.events.repository.BudgetArticleRepository articles;
    private final int wanted;

    public EventBookSeeder(EventProjectRepository events, EventBudgetRepository budgets,
            LeadInquiryRepository leads, ContactRepository contacts,
            com.weddingplanner.crm.events.repository.BudgetArticleRepository articles,
            @Value("${planner.events.demo-events:20}") int wanted) {
        this.events = events; this.budgets = budgets; this.leads = leads; this.contacts = contacts;
        this.articles = articles; this.wanted = wanted;
    }

    @Override
    public void run(String... args) {
        var templates = templates();
        if (templates.isEmpty()) return;
        // The imported examples are events too, so the book is topped up to the wanted size rather
        // than added to it — and a demo database that already has its weddings is left alone.
        int existing = events.findAllActive().size();
        if (existing >= wanted) return;

        var booked = leads.findAllActive().stream()
                .filter(LeadInquiry::isBooked)
                .filter(lead -> lead.getContact() != null)
                .filter(lead -> lead.getCoupleName() != null && !lead.getCoupleName().isBlank())
                .filter(lead -> lead.getWeddingDate() != null)
                .sorted(Comparator.comparing(LeadInquiry::getWeddingDate))
                .toList();
        if (booked.isEmpty()) return;

        // Spread the picks across the booked year rather than taking the earliest ones, so the book
        // holds the weddings imminent as well as the ones still being planned.
        int needed = wanted - existing;
        var picks = spread(booked, needed);
        for (int i = 0; i < picks.size(); i++) book(picks.get(i), templates.get(i % templates.size()));

        // The demand history covers one year, and a wedding booked in it has not happened yet — so
        // a book built only from it is a business that has never delivered a wedding. The rest of
        // the shelf is last season's couples, whose inquiries predate the history entirely.
        int remaining = wanted - events.findAllActive().size();
        for (int i = 0; i < remaining && i < PAST.size(); i++)
            past(PAST.get(i), templates.get((picks.size() + i) % templates.size()), i);
    }

    /**
     * Couples married before the demo's demand history begins. They have no inquiry to be booked
     * from — that is the point — so their contact cards are created here, as two people linked by
     * partner, exactly as a split couple card ends up.
     */
    private record Past(String couple, String region, int guests, int monthsAgo, String budget) {}

    private static final List<Past> PAST = List.of(
            new Past("Giulia & Tommaso", "Lake Como", 120, 4, "480000"),
            new Past("Sofia & Luca", "Amalfi Coast", 90, 8, "310000"),
            new Past("Clara & Antoine", "Tuscany", 140, 11, "560000"),
            new Past("Maya & Idris", "Puglia", 75, 14, "240000"),
            new Past("Eleanor & Rupert", "Lake Como", 160, 17, "720000"),
            new Past("Nadia & Karim", "Sicily", 105, 20, "395000"));

    private void past(Past wedding, EventBudget template, int index) {
        UUID eventId = id("event", "past:" + wedding.couple());
        if (events.findById(eventId).isPresent()) return;

        var random = new Random(eventId.getMostSignificantBits());
        var people = com.weddingplanner.crm.service.CoupleNames.split(wedding.couple());
        var client = person(people.getFirst(), wedding.couple(), 0);
        var partner = people.size() > 1 ? person(people.get(1), wedding.couple(), 1) : null;
        if (partner != null) {
            client.setPartner(Ref.of(Contact.class, partner.getId()));
            partner.setPartner(Ref.of(Contact.class, client.getId()));
            contacts.save(client);
            contacts.save(partner);
        }

        String[] pool = VENUES.getOrDefault(wedding.region(), FALLBACK_VENUES);
        String venue = pool[random.nextInt(pool.length)];
        // Weddings happen on a Saturday, and a demo that puts one on a Tuesday reads as generated.
        LocalDate date = LocalDate.now().minusMonths(wedding.monthsAgo())
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SATURDAY));

        var event = new EventProject();
        event.setId(eventId);
        event.setDescription(wedding.couple());
        event.setClient(Ref.of(Contact.class, client.getId()));
        event.setStartDate(date);
        event.setGuests(wedding.guests());
        event.setLocation(venue + ", " + wedding.region());
        event.setStage(EventStage.COMPLETED);
        event.setNotes("Delivered before the demo's demand history begins, so it has no inquiry."
                + " Demo figures: the estimate is an imported example re-priced to this couple's budget.");
        events.save(event);

        var budget = estimate(event, new BigDecimal(wedding.budget()), template, venue, random);
        budgets.save(budget);
        event.setSelectedBudget(Ref.of(EventBudget.class, budget.getId()));
        events.save(event);
    }

    /** One half of a past couple, at a stable id so a restart neither duplicates nor revives them. */
    private Contact person(String name, String couple, int position) {
        UUID id = id("contact", couple + "#" + position);
        var existing = contacts.findById(id);
        if (existing.isPresent()) return existing.get();
        var contact = new Contact();
        contact.setId(id);
        contact.setDescription(name);
        contact.setInboxFolder(com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        return contacts.save(contact);
    }

    /** Evenly spaced picks from a date-ordered list; the whole list when it is already short enough. */
    private static <T> List<T> spread(List<T> values, int wanted) {
        if (wanted >= values.size()) return values;
        var picked = new ArrayList<T>(wanted);
        for (int i = 0; i < wanted; i++) picked.add(values.get((int) ((long) i * values.size() / wanted)));
        return picked;
    }

    /** The imported PDF estimates, which are the only authored line structure in the demo. */
    private List<EventBudget> templates() {
        return budgets.findAllActive().stream()
                .filter(budget -> budget.getSourceFile() != null && !budget.getSourceFile().isBlank())
                .filter(budget -> budget.getItems() != null && budget.getItems().size() > 5)
                .sorted(Comparator.comparing(EventBudget::getScenario, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("planner:book:v1:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private void book(LeadInquiry lead, EventBudget template) {
        UUID eventId = id("event", lead.getId().toString());
        // Include tombstones: a wedding deleted in the demo must not come back on the next restart.
        if (events.findById(eventId).isPresent()) return;

        var random = new Random(lead.getId().getMostSignificantBits());
        int guests = lead.getGuestCount() == null ? 60 + random.nextInt(80) : lead.getGuestCount();
        String region = lead.getPreferredLocation() == null || lead.getPreferredLocation().isBlank()
                ? "Lake Como" : lead.getPreferredLocation();
        String[] pool = VENUES.getOrDefault(region, FALLBACK_VENUES);
        String venue = pool[random.nextInt(pool.length)];

        var event = new EventProject();
        event.setId(eventId);
        // The couple's own name, as they wrote it — this is how the planner refers to the wedding.
        event.setDescription(lead.getCoupleName());
        event.setClient(lead.getContact());
        event.setStartDate(lead.getWeddingDate());
        event.setGuests(guests);
        event.setLocation(venue + ", " + region);
        event.setStage(stage(lead.getWeddingDate()));
        event.setNotes("Booked from inquiry " + lead.getNumber() + ". Demo figures: the estimate is an"
                + " imported example re-priced to this couple's stated budget.");
        events.save(event);

        var budget = estimate(event, lead.getBudget(), template, venue, random);
        budgets.save(budget);
        event.setSelectedBudget(Ref.of(EventBudget.class, budget.getId()));
        events.save(event);
    }

    /**
     * Where a wedding stands is its date, not a field somebody remembered to update: the day has
     * happened or it has not, and the next season is confirmed while the rest is still planning.
     */
    static EventStage stage(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.isBefore(today)) return EventStage.COMPLETED;
        // Every wedding here came from a lead that said yes, so the line is not whether it is
        // booked but whether it is this year's work: inside twelve months the contract is signed
        // and suppliers are being committed, beyond it the date is held and little else.
        return date.isBefore(today.plusYears(1)) ? EventStage.CONFIRMED : EventStage.PLANNING;
    }

    /**
     * The couple's estimate: the example's articles and comments, re-priced so the known subtotal
     * lands near the budget they stated, and trimmed to the days this wedding actually runs. Price
     * status is carried across, so an article the example never priced stays unpriced here — a
     * scenario with nothing left to confirm would be the one dishonest thing in the book.
     */
    private EventBudget estimate(EventProject event, BigDecimal stated, EventBudget template,
            String venue, Random random) {
        // Roughly two in five weddings are the day itself and its planning; the rest carry the
        // welcome dinner and the brunch as well.
        boolean singleDay = random.nextInt(5) < 2;
        var budget = new EventBudget();
        budget.setId(id("budget", event.getId().toString()));
        budget.setEvent(Ref.of(EventProject.class, event.getId()));
        budget.setScenario(venue);
        budget.setPriceBasis("Demo figures, VAT to confirm");

        var lines = template.getItems().stream()
                .filter(line -> !singleDay || line.getPhase() == EventPhase.WEDDING
                        || line.getPhase() == EventPhase.PLANNING)
                .filter(line -> !namesAnotherVenue(articleName(line), venue))
                .toList();
        BigDecimal quoted = lines.stream()
                .filter(line -> line.getPriceState() == BudgetPriceState.QUOTED)
                .map(line -> amount(line.getQuantity(), line.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal factor = factor(stated, quoted);

        for (var source : lines) {
            var line = new EventBudgetLine();
            line.setPhase(source.getPhase());
            line.setArticle(source.getArticle());
            line.setDetails(covers(source.getDetails()));
            line.setKind(source.getKind());
            line.setPriceState(source.getPriceState());
            line.setQuantity(source.getQuantity());
            line.setUnitPrice(source.getUnitPrice() == null ? null : price(source.getUnitPrice(), factor));
            budget.getItems().add(line);
        }
        return budget;
    }

    /**
     * What an article covers, taken from the example's comment but with the example's own prices
     * left behind. The imported wording names the venue that quoted it and the euros it quoted —
     * true of that estimate, false of this one.
     *
     * <p>A comment is kept only down to its first priced line. The importer's own "Source value"
     * tail and a closing "from €300 per person" sit after the description, so cutting there keeps
     * the description; a priced bullet in the middle of a list does not, and there the whole
     * comment goes rather than leaving the half of a list that happened to have no figures in it.
     */
    static String covers(String details) {
        if (details == null || details.isBlank()) return null;
        var kept = new ArrayList<String>();
        boolean cut = false;
        for (String raw : details.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (priced(line)) { cut = true; continue; }
            // Prose after a price is the rest of a list whose priced items have just been removed.
            if (cut) return null;
            kept.add(line);
        }
        return kept.isEmpty() ? null : String.join("\n", kept);
    }

    private static boolean priced(String line) {
        return line.matches("(?i).*source value.*")
                || line.matches("(?i).*(\\d[\\d.,]*\\s*(€|euro)|(€|euro)\\s*[\\d.,]+).*")
                || line.matches("(?i)^\\d[\\d.,]*\\s+per\\s+person.*");
    }

    /**
     * An article that names the venue that quoted it — "Extra costs Villa Balbiano" — belongs to
     * that estimate and reads as a mistake on anybody else's. The couple's own venue keeps its
     * articles; the other examples' venues do not travel.
     */
    private static boolean namesAnotherVenue(String article, String venue) {
        if (article == null) return false;
        var lower = article.toLowerCase(Locale.ROOT);
        if (venue != null && lower.contains(venue.toLowerCase(Locale.ROOT))) return false;
        return lower.matches(".*\\b(villa|masseria|palazzo|castello|borgo|tenuta|convento)\\s+\\p{L}+.*");
    }

    /** What this couple's budget is, against what the example costs; bounded so nothing reads absurd. */
    private static BigDecimal factor(BigDecimal stated, BigDecimal quoted) {
        if (stated == null || stated.signum() <= 0 || quoted.signum() <= 0) return BigDecimal.ONE;
        var ratio = stated.divide(quoted, 4, RoundingMode.HALF_UP);
        return ratio.max(new BigDecimal("0.35")).min(new BigDecimal("2.50"));
    }

    /** Re-priced to the nearest fifty euro: a quote reads as a quote, not as a spreadsheet result. */
    private static BigDecimal price(BigDecimal unit, BigDecimal factor) {
        var scaled = unit.multiply(factor);
        if (scaled.compareTo(new BigDecimal("50")) < 0) return scaled.setScale(2, RoundingMode.HALF_UP);
        return scaled.divide(new BigDecimal("50"), 0, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("50")).setScale(2, RoundingMode.HALF_UP);
    }

    private String articleName(EventBudgetLine line) {
        return line.getArticle() == null ? null
                : articles.findById(line.getArticle().id()).map(a -> a.getDescription()).orElse(null);
    }

    private static BigDecimal amount(BigDecimal quantity, BigDecimal unit) {
        if (quantity == null || unit == null) return BigDecimal.ZERO;
        return quantity.multiply(unit);
    }

    /** Kept for the seeders that follow: the couple, as two cards, behind one event. */
    static List<Contact> household(ContactRepository contacts, Ref<Contact> client) {
        if (client == null) return List.of();
        var first = contacts.findActiveById(client.id()).orElse(null);
        if (first == null) return List.of();
        var partner = first.getPartner() == null ? null
                : contacts.findActiveById(first.getPartner().id()).orElse(null);
        return partner == null ? List.of(first) : List.of(first, partner);
    }
}
