package com.weddingplanner.crm.events.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import su.onno.types.Ref;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;
import com.weddingplanner.crm.events.service.EventBudgetReport;

/**
 * The couple's own page for their wedding, reached by the link a planner shares from the event page.
 *
 * <p>It lives outside {@code /api/**}, which is the only part of the app that asks for a session, so
 * the couple opens it without an account; the unguessable token in the path is the whole of the
 * access check. What it prints is chosen for a client: the dates and venue, the estimate the event
 * runs on at the price the couple was quoted, what they have been invoiced and paid, and who from
 * the planning team is looking after them. Supplier names, costs, margins, commissions and internal
 * notes never reach this page.
 *
 * <p>Rendered on the server as one self-contained HTML page — no SPA, no scripts — so it opens fast
 * on a phone, prints cleanly to PDF and cannot leak anything the markup does not carry.
 */
@RestController
public class ClientEventPageController {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** ILES's logo from their own site's CDN (ilesevents.it), hotlinked for the prototype. */
    private static final String LOGO = "https://static.tildacdn.net/tild3330-3732-4163-b339-353764663238/Mask_group_1.png";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final EventInvoiceRepository invoices;
    private final EventPaymentRepository payments;
    private final EventPartyRepository parties;
    private final ContactRepository contacts;
    private final EventBudgetReport report;

    public ClientEventPageController(EventProjectRepository events, EventBudgetRepository budgets,
            EventInvoiceRepository invoices, EventPaymentRepository payments, EventPartyRepository parties,
            ContactRepository contacts, EventBudgetReport report) {
        this.events = events; this.budgets = budgets; this.invoices = invoices; this.payments = payments;
        this.parties = parties; this.contacts = contacts; this.report = report;
    }

    /** 256 random bits, URL-safe: a link, not a password, but one nobody can guess or enumerate. */
    static String newToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @GetMapping(value = "/client/event/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<String> page(@PathVariable String token) {
        var event = token == null || token.length() < 32 ? null : events.findAllActive().stream()
                .filter(e -> token.equals(e.getShareToken())).findFirst().orElse(null);
        if (event == null) return html(HttpStatus.NOT_FOUND, notFound());
        return html(HttpStatus.OK, render(event));
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    private static boolean owns(Ref<EventProject> ref, UUID id) { return ref != null && id.equals(ref.id()); }

    private String contact(Ref<Contact> ref) {
        return ref == null ? null : contacts.findById(ref.id()).map(Contact::getDescription).orElse(null);
    }

    private String render(EventProject event) {
        var id = event.getId();
        var budget = event.getSelectedBudget() == null ? null
                : budgets.findActiveById(event.getSelectedBudget().id()).filter(b -> owns(b.getEvent(), id)).orElse(null);
        var breakdown = report.breakdown(event, budget);
        var billed = invoices.findAllActive().stream()
                .filter(i -> owns(i.getEvent(), id) && i.isPosted() && i.getDirection() == InvoiceDirection.CLIENT)
                .sorted(Comparator.comparing(EventInvoice::getDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        var billedIds = billed.stream().map(EventInvoice::getId).collect(java.util.stream.Collectors.toSet());
        var received = payments.findAllActive().stream()
                .filter(v -> v.isPosted() && v.getDirection() == InvoiceDirection.CLIENT
                        && (owns(v.getEvent(), id) || (v.getInvoice() != null && billedIds.contains(v.getInvoice().id()))))
                .toList();
        var team = parties.findAllActive().stream()
                .filter(p -> owns(p.getEvent(), id) && p.getRole() == InboxFolder.TEAM)
                .map(p -> new String[]{contact(p.getContact()), p.getScope()})
                .filter(p -> p[0] != null).toList();

        var name = blank(event.getDescription()) ? event.getCode() : event.getDescription();
        var out = new StringBuilder(16_000);
        out.append(head(name));
        out.append("<main>");

        // The wedding itself.
        out.append("<section class=\"hero wrap\"><h1>").append(esc(name)).append("</h1><dl class=\"facts\">");
        fact(out, "Date", when(event.getStartDate(), event.getEndDate()));
        fact(out, "Location", blank(event.getLocation()) ? "To be confirmed" : event.getLocation());
        fact(out, "Guests", event.getGuests() == null ? "To be confirmed" : event.getGuests().toString());
        var days = event.getStartDate() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), event.getStartDate());
        if (days != null && days > 0) fact(out, "Countdown", days + (days == 1 ? " day to go" : " days to go"));
        out.append("</dl></section>");

        // The estimate, category by category, at the price the couple was quoted.
        out.append("<section class=\"wrap\">");
        if (budget == null) {
            out.append("<h2>Estimate</h2><p class=\"quiet\">We are still putting your estimate together. It will appear here as soon as it is ready.</p>");
        } else {
            var total = breakdown.categories().stream().flatMap(c -> c.lines().stream())
                    .filter(EventBudgetReport.Line::budgeted).map(EventBudgetReport.Line::amount)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            out.append("<div class=\"row-head\"><h2>Estimate</h2><p class=\"total\">").append(money(total)).append("</p></div>");
            var notes = new ArrayList<String>();
            if (!blank(budget.getScenario())) notes.add(esc(EventBudgetReport.label(budget)));
            if (!blank(budget.getPriceBasis())) notes.add(esc(budget.getPriceBasis()));
            var open = zero(budget.getUnknownItems());
            if (open > 0) notes.add(open + (open == 1 ? " item" : " items") + " still to be priced");
            if (budget.getRefundableDeposit() != null && budget.getRefundableDeposit().signum() > 0)
                notes.add("refundable deposit of " + money(budget.getRefundableDeposit()) + " held separately");
            if (!notes.isEmpty()) out.append("<p class=\"note\">").append(String.join(" · ", notes)).append("</p>");
            for (var category : breakdown.categories()) {
                var lines = category.lines().stream().filter(EventBudgetReport.Line::budgeted).toList();
                if (lines.isEmpty()) continue;
                out.append("<div class=\"cat\"><div class=\"cat-head\"><h3>").append(esc(category.category()))
                        .append("</h3><span>").append(money(category.estimated())).append("</span></div><ul>");
                for (var line : lines) {
                    out.append("<li><div class=\"what\"><p class=\"item\">").append(esc(line.article())).append("</p>");
                    var sub = new ArrayList<String>();
                    if (line.phaseLabel() != null && !"—".equals(line.phaseLabel())) sub.add(esc(line.phaseLabel()));
                    if (!blank(line.details())) sub.add(esc(line.details().strip()));
                    if (!sub.isEmpty()) out.append("<p class=\"details\">").append(String.join(" — ", sub)).append("</p>");
                    out.append("</div><p class=\"amt\">");
                    if (line.amount() != null) out.append(money(line.amount()));
                    else out.append("<span class=\"state\">").append(esc(line.stateLabel())).append("</span>");
                    out.append("</p></li>");
                }
                out.append("</ul></div>");
            }
        }
        out.append("</section>");

        // Payments: what has been invoiced to the couple and what has arrived.
        var invoiced = billed.stream().map(EventInvoice::getTotal).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        var paid = received.stream().map(EventPayment::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        out.append("<section class=\"wrap\"><h2>Payments</h2>");
        if (billed.isEmpty() && received.isEmpty()) {
            out.append("<p class=\"quiet\">No invoices yet. We will send the payment schedule once the estimate is agreed.</p>");
        } else {
            out.append("<dl class=\"sums\">");
            sum(out, "Invoiced", money(invoiced));
            sum(out, "Paid", money(paid));
            sum(out, "Balance due", money(invoiced.subtract(paid).max(BigDecimal.ZERO)));
            out.append("</dl><div class=\"scroll\"><table><thead><tr><th>Invoice</th><th>Due</th><th class=\"r\">Amount</th><th class=\"r\">Paid</th></tr></thead><tbody>");
            for (var invoice : billed) {
                var settled = received.stream().filter(v -> v.getInvoice() != null && v.getInvoice().id().equals(invoice.getId()))
                        .map(EventPayment::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                out.append("<tr><td>").append(esc(invoice.getNumber())).append("</td><td>")
                        .append(invoice.getDueDate() == null ? "—" : SHORT.format(invoice.getDueDate())).append("</td><td class=\"r\">")
                        .append(money(invoice.getTotal())).append("</td><td class=\"r\">").append(money(settled)).append("</td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        out.append("</section>");

        if (!team.isEmpty()) {
            out.append("<section class=\"wrap\"><h2>Your team</h2><ul class=\"team\">");
            for (var member : team) {
                out.append("<li><p class=\"item\">").append(esc(member[0])).append("</p>");
                if (!blank(member[1])) out.append("<p class=\"details\">").append(esc(member[1])).append("</p>");
                out.append("</li>");
            }
            out.append("</ul></section>");
        }

        out.append("</main><footer class=\"wrap foot\"><img class=\"logo-sm\" src=\"").append(LOGO)
                .append("\" alt=\"ILES Events\"><p>Updated ").append(DAY.format(LocalDate.now()))
                .append(". Figures are estimates until confirmed in writing.</p></footer></body></html>");
        return out.toString();
    }

    private static void fact(StringBuilder out, String label, String value) {
        out.append("<div><dt>").append(esc(label)).append("</dt><dd>").append(esc(value)).append("</dd></div>");
    }

    private static void sum(StringBuilder out, String label, String value) {
        out.append("<div><dt>").append(esc(label)).append("</dt><dd>").append(value).append("</dd></div>");
    }

    private static String when(LocalDate start, LocalDate end) {
        if (start == null) return "To be confirmed";
        if (end == null || end.equals(start)) return DAY.format(start);
        if (start.getYear() == end.getYear() && start.getMonth() == end.getMonth())
            return start.getDayOfMonth() + "–" + DAY.format(end);
        return SHORT.format(start) + " – " + SHORT.format(end);
    }

    /** "€ 12,500" — whole euros where the amount is whole, cents only where they exist. */
    static String money(BigDecimal value) {
        var amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        var format = new DecimalFormat(amount.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return "€&nbsp;" + format.format(amount);
    }

    private static int zero(Integer value) { return value == null ? 0 : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static String esc(String value) {
        if (value == null) return "";
        var out = new StringBuilder(value.length() + 16);
        for (var c : value.toCharArray()) switch (c) {
            case '<' -> out.append("&lt;");
            case '>' -> out.append("&gt;");
            case '&' -> out.append("&amp;");
            case '"' -> out.append("&quot;");
            case '\'' -> out.append("&#39;");
            default -> out.append(c);
        }
        return out.toString();
    }

    private static String notFound() {
        return head("Link unavailable") + "<main><section class=\"wrap gone\"><h2>This page is no longer shared.</h2>"
                + "<p class=\"quiet\">Ask us for a new link.</p></section></main></body></html>";
    }

    /** The ILES look: Poppins at light weight, near-black on white, hairline rules, lots of air. */
    private static String head(String title) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="robots" content="noindex,nofollow">
            <title>%s · ILES Events</title>
            <link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Poppins:wght@200;300;400;500&display=swap" rel="stylesheet">
            <style>
            :root{--fg:#111;--text:#464646;--muted:#8a8a8a;--rule:#e8e8e8}
            *{box-sizing:border-box;margin:0;padding:0}
            html{-webkit-text-size-adjust:100%%;color-scheme:light}
            body{background:#fff;color:var(--text);font:300 16px/1.65 'Poppins',Arial,sans-serif;-webkit-font-smoothing:antialiased}
            img{display:block;max-width:100%%}
            .wrap{max-width:1080px;margin:0 auto;padding:0 24px}
            .hero{padding-top:96px;padding-bottom:72px}
            h1{color:var(--fg);font-weight:300;font-size:clamp(40px,7vw,84px);line-height:1.02;letter-spacing:-.035em;margin-bottom:56px;overflow-wrap:anywhere}
            .facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:28px;padding-top:28px;border-top:1px solid var(--rule)}
            .facts dt{font-size:12px;font-weight:400;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
            .facts dd{color:var(--fg);font-size:18px;margin-top:6px}
            section.wrap{padding-top:40px;padding-bottom:96px}
            h2{color:var(--fg);font-weight:300;font-size:clamp(30px,4vw,44px);letter-spacing:-.02em;line-height:1.1;margin-bottom:24px}
            .row-head{display:flex;justify-content:space-between;align-items:baseline;gap:24px;flex-wrap:wrap}
            .row-head h2{margin-bottom:0}
            .total{color:var(--fg);font-size:clamp(30px,4vw,44px);font-weight:300;letter-spacing:-.02em;white-space:nowrap}
            .note{color:var(--muted);font-size:14px;margin:10px 0 24px}
            .quiet{color:var(--muted);max-width:560px}
            .cat{margin-top:48px}
            .cat-head{display:flex;justify-content:space-between;align-items:baseline;gap:16px;border-bottom:1px solid var(--fg);padding-bottom:10px}
            h3{color:var(--fg);font-weight:400;font-size:19px}
            .cat-head span{color:var(--fg);white-space:nowrap}
            ul{list-style:none}
            .cat li{display:flex;justify-content:space-between;gap:24px;padding:16px 0;border-bottom:1px solid var(--rule)}
            .what{min-width:0;flex:1}
            .item{color:var(--fg);font-weight:400}
            .details{color:var(--muted);font-size:14px;white-space:pre-line;margin-top:2px;max-width:680px;overflow-wrap:anywhere}
            .amt{white-space:nowrap;color:var(--fg);text-align:right}
            .state{color:var(--muted);font-size:14px}
            .sums{display:grid;grid-template-columns:repeat(3,1fr);gap:24px;margin-bottom:40px}
            .sums dt{color:var(--muted);font-size:14px}
            .sums dd{color:var(--fg);font-size:28px;font-weight:300;letter-spacing:-.01em}
            table{width:100%%;border-collapse:collapse;font-size:15px}
            th{text-align:left;font-weight:400;color:var(--muted);font-size:14px;padding:0 0 10px;border-bottom:1px solid var(--fg)}
            td{padding:14px 0;border-bottom:1px solid var(--rule);color:var(--fg)}
            th+th,td+td{padding-left:16px}.r{text-align:right}td.r{white-space:nowrap}
            .scroll{overflow-x:auto}
            .team{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));column-gap:40px}
            .team li{padding:16px 0;border-bottom:1px solid var(--rule)}
            .foot{display:flex;flex-direction:column;align-items:center;gap:16px;text-align:center;padding-top:24px;padding-bottom:64px;font-size:13px;color:var(--muted)}
            .logo-sm{width:90px;height:auto}
            .gone{text-align:center;padding-top:96px}.gone .quiet{margin:0 auto}
            @media (max-width:760px){
             .hero{padding-top:56px;padding-bottom:48px}
             section.wrap{padding-bottom:64px}
             .cat li{gap:12px}
             .sums{grid-template-columns:1fr;gap:12px}.sums div{display:flex;justify-content:space-between;align-items:baseline;border-bottom:1px solid var(--rule);padding-bottom:10px}.sums dd{font-size:22px}
             table{font-size:13px}th+th,td+td{padding-left:10px}
            }
            @media print{.hero{padding:32px 0}section.wrap{padding-bottom:32px}.cat li{break-inside:avoid}}
            </style></head><body>
            """.formatted(esc(title));
    }
}
