package com.weddingplanner.crm.events.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import su.onno.types.Ref;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;

/**
 * Where an event's money actually goes, article by article.
 *
 * <p>A percentage tells you a category is 80% committed; it does not tell you which florist holds
 * the other 20%. This report answers the second question: for every line of the chosen estimate,
 * what was quoted, who it was quoted by, which supplier invoices were raised against it, and how
 * much of those is paid. Spend booked against an article the estimate never carried is kept too —
 * unbudgeted is exactly the thing a planner needs to see.
 *
 * <p>One report backs the event workspace and the exported workbook, so the screen and the
 * spreadsheet can never disagree.
 */
@Service
public class EventBudgetReport {
    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final EventInvoiceRepository invoices;
    private final EventPaymentRepository payments;
    private final BudgetArticleRepository articles;
    private final BudgetCategoryRepository categories;
    private final ContactRepository contacts;

    public EventBudgetReport(EventProjectRepository events, EventBudgetRepository budgets,
            EventInvoiceRepository invoices, EventPaymentRepository payments, BudgetArticleRepository articles,
            BudgetCategoryRepository categories, ContactRepository contacts) {
        this.events = events; this.budgets = budgets; this.invoices = invoices; this.payments = payments;
        this.articles = articles; this.categories = categories; this.contacts = contacts;
    }

    /** One supplier invoice line raised against a budget article, and the cash already sent for it. */
    public record Commitment(UUID invoiceId, String number, String counterparty, String details,
            BigDecimal amount, BigDecimal paid, boolean posted) {}

    /**
     * One row of the estimate — or, when {@code budgeted} is false, spend that no estimate line
     * covers. {@code amount} is null for an article that is still unpriced.
     */
    public record Line(UUID id, String category, String article, UUID articleId, String phase, String phaseLabel,
            String details, String contractor, UUID contractorId, String state, String stateLabel,
            String kind, String kindLabel, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
            BigDecimal committed, BigDecimal paid, boolean budgeted, List<Commitment> commitments) {}

    /** A budget category with the lines underneath it, so the table can be opened rather than trusted. */
    public record Category(String category, BigDecimal estimated, BigDecimal committed, BigDecimal paid,
            int unpriced, List<Line> lines) {}

    /** An event's chosen estimate, broken down; {@code budget} is null when no scenario is selected. */
    public record Breakdown(EventProject event, EventBudget budget, List<Category> categories,
            BigDecimal estimated, BigDecimal committed, BigDecimal paid) {}

    /** One column of the scenario comparison: an estimate and the amount it puts on each row. */
    public record Scenario(EventBudget budget, BigDecimal total) {}

    /**
     * The scenarios of one event laid out side by side, the shape the estimates arrive in as PDFs:
     * a row per category/position/comment, a column per scenario, a total at the foot.
     */
    public record Comparison(EventProject event, List<Scenario> scenarios, List<ComparisonRow> rows,
            boolean commentsDiffer) {}

    /** {@code amounts} is positional against {@link Comparison#scenarios()}; null means "not in it". */
    public record ComparisonRow(String category, String article, String details, String phaseLabel,
            String kindLabel, List<BigDecimal> amounts, List<String> states) {}

    private static final Map<EventPhase, String> PHASES = Map.of(
            EventPhase.WEDDING, "Wedding day",
            EventPhase.WELCOME, "Welcome / rehearsal",
            EventPhase.BRUNCH, "Brunch / after wedding",
            EventPhase.PLANNING, "Planning & coordination");
    private static final Map<ChargeKind, String> KINDS = Map.of(
            ChargeKind.SERVICE, "Service",
            ChargeKind.AGENCY_FEE, "Agency fee",
            ChargeKind.DEPOSIT, "Refundable deposit");
    private static final Map<BudgetPriceState, String> STATES = Map.of(
            BudgetPriceState.QUOTED, "Quoted",
            BudgetPriceState.TBD, "To be confirmed",
            BudgetPriceState.INCLUDED, "Included",
            BudgetPriceState.NOT_REQUIRED, "Not required");

    public static String phaseLabel(EventPhase phase) { return phase == null ? "—" : PHASES.getOrDefault(phase, phase.name()); }
    public static String stateLabel(BudgetPriceState state) { return state == null ? "—" : STATES.getOrDefault(state, state.name()); }
    public static String kindLabel(ChargeKind kind) { return kind == null ? "—" : KINDS.getOrDefault(kind, kind.name()); }

    public Optional<EventProject> event(UUID id) { return events.findActiveById(id); }
    public Optional<EventBudget> budget(UUID id) { return budgets.findActiveById(id); }

    public List<EventBudget> scenarios(UUID eventId) {
        return budgets.findAllActive().stream().filter(b -> owns(b.getEvent(), eventId))
                .sorted(Comparator.comparing((EventBudget b) -> b.getScenario() == null ? "" : b.getScenario())
                        .thenComparing(b -> b.getRevision() == null ? 1 : b.getRevision()))
                .toList();
    }

    private static boolean owns(Ref<EventProject> ref, UUID eventId) {
        return ref != null && eventId != null && eventId.equals(ref.id());
    }

    public String contact(Ref<Contact> ref) {
        return ref == null ? null : contacts.findById(ref.id()).map(Contact::getDescription).orElse("Unavailable contact");
    }

    public String article(Ref<BudgetArticle> ref) {
        return ref == null ? "—" : articles.findById(ref.id()).map(BudgetArticle::getDescription).orElse("Unavailable article");
    }

    /** The catalog category of an article, falling back to the legacy text it was imported with. */
    public String category(Ref<BudgetArticle> ref) {
        if (ref == null) return "Uncategorised";
        return articles.findById(ref.id()).map(a -> {
            if (a.getBudgetCategory() != null) {
                var named = categories.findById(a.getBudgetCategory().id()).map(BudgetCategory::getDescription).orElse(null);
                if (named != null && !named.isBlank()) return pretty(named);
            }
            return a.getCategory() == null || a.getCategory().isBlank() ? "Uncategorised" : pretty(a.getCategory());
        }).orElse("Uncategorised");
    }

    /**
     * Categories imported from the phase name arrive shouting ("WEDDING"). Title-case those and
     * leave authored names alone, so a workbook reads like a document rather than a database dump.
     */
    static String pretty(String name) {
        if (!name.matches("[A-Z0-9 _-]+")) return name;
        var words = name.toLowerCase(Locale.ROOT).replace('_', ' ').split(" ");
        return Arrays.stream(words).filter(w -> !w.isBlank())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    /**
     * Break an estimate down by category. Supplier commitments are matched to estimate lines by
     * budget article — the same key the "commit to supplier" action copies across — and a payment,
     * which settles a whole invoice, is split over that invoice's lines in proportion to their
     * amounts.
     */
    public Breakdown breakdown(EventProject event, EventBudget budget) {
        var eventId = event.getId();
        var posted = invoices.findAllActive().stream()
                .filter(i -> owns(i.getEvent(), eventId) && i.isPosted() && i.getDirection() == InvoiceDirection.SUPPLIER)
                .toList();
        var settled = payments.findAllActive().stream().filter(v -> v.isPosted() && v.getInvoice() != null).toList();

        // article -> the supplier invoice lines booked against it, with their share of the cash paid.
        var commitmentsOf = new LinkedHashMap<UUID, List<Commitment>>();
        for (var invoice : posted) {
            var cash = settled.stream().filter(v -> v.getInvoice().id().equals(invoice.getId()))
                    .map(EventPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var total = zero(invoice.getTotal());
            for (var line : invoice.getItems()) {
                if (line.getArticle() == null) continue;
                var amount = zero(line.getAmount());
                var paid = total.signum() == 0 ? BigDecimal.ZERO
                        : cash.multiply(amount).divide(total, 2, RoundingMode.HALF_UP);
                commitmentsOf.computeIfAbsent(line.getArticle().id(), key -> new ArrayList<>())
                        .add(new Commitment(invoice.getId(), invoice.getNumber(), contact(invoice.getCounterparty()),
                                line.getDetails(), amount, paid, true));
            }
        }

        var byCategory = new LinkedHashMap<String, List<Line>>();
        var seen = new HashSet<UUID>();
        if (budget != null) for (var line : budget.getItems()) {
            var articleId = line.getArticle() == null ? null : line.getArticle().id();
            if (articleId != null) seen.add(articleId);
            var commitments = articleId == null ? List.<Commitment>of()
                    : commitmentsOf.getOrDefault(articleId, List.of());
            byCategory.computeIfAbsent(category(line.getArticle()), key -> new ArrayList<>()).add(new Line(
                    line.getId(), category(line.getArticle()), article(line.getArticle()), articleId,
                    line.getPhase() == null ? null : line.getPhase().name(), phaseLabel(line.getPhase()),
                    line.getDetails(), contact(line.getContractor()),
                    line.getContractor() == null ? null : line.getContractor().id(),
                    line.getPriceState() == null ? null : line.getPriceState().name(), stateLabel(line.getPriceState()),
                    line.getKind() == null ? null : line.getKind().name(), kindLabel(line.getKind()),
                    line.getQuantity(), line.getUnitPrice(), line.getAmount(),
                    sum(commitments, Commitment::amount), sum(commitments, Commitment::paid), true, commitments));
        }
        // Money already committed against an article this scenario does not carry is the finding, not
        // a rounding error — it gets its own row rather than vanishing into a category total.
        for (var entry : commitmentsOf.entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            var ref = Ref.of(BudgetArticle.class, entry.getKey());
            byCategory.computeIfAbsent(category(ref), key -> new ArrayList<>()).add(new Line(
                    null, category(ref), article(ref), entry.getKey(), null, "—",
                    "Committed outside the selected estimate", null, null, null, "Unbudgeted", null, "—",
                    null, null, null, sum(entry.getValue(), Commitment::amount),
                    sum(entry.getValue(), Commitment::paid), false, entry.getValue()));
        }

        var rows = byCategory.entrySet().stream().map(entry -> new Category(entry.getKey(),
                sum(entry.getValue(), l -> zero(l.amount())),
                sum(entry.getValue(), Line::committed), sum(entry.getValue(), Line::paid),
                (int) entry.getValue().stream().filter(l -> l.budgeted() && l.amount() == null).count(),
                entry.getValue())).toList();
        return new Breakdown(event, budget, rows,
                sum(rows, Category::estimated), sum(rows, Category::committed), sum(rows, Category::paid));
    }

    private static <T> BigDecimal sum(Collection<T> values, java.util.function.Function<T, BigDecimal> of) {
        return values.stream().map(of).map(EventBudgetReport::zero).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Every scenario of an event on one grid. Rows are keyed on category + article + details, so a
     * line quoted by two venues lines up on the same row and the columns can be read across.
     */
    public Comparison comparison(EventProject event) {
        var scenarios = scenarios(event.getId());
        var order = new LinkedHashMap<String, ComparisonRow>();
        var amounts = new LinkedHashMap<String, BigDecimal[]>();
        var states = new LinkedHashMap<String, String[]>();
        var divergent = new boolean[1];
        for (int column = 0; column < scenarios.size(); column++) {
            for (var line : scenarios.get(column).getItems()) {
                var category = category(line.getArticle());
                var article = article(line.getArticle());
                // Keyed on the position, not on its comment text: a line quoted by three venues is one
                // row with three money columns, which is how the venue quotes themselves read. Where
                // the venues wrote different comments the first is kept, and the sheet says so.
                var key = category + " | " + article + " | " + phaseLabel(line.getPhase());
                var existing = order.get(key);
                if (existing != null && line.getDetails() != null && !line.getDetails().isBlank()
                        && !line.getDetails().equals(existing.details())) divergent[0] = true;
                order.computeIfAbsent(key, k -> new ComparisonRow(category, article, line.getDetails(),
                        phaseLabel(line.getPhase()), kindLabel(line.getKind()), null, null));
                amounts.computeIfAbsent(key, k -> new BigDecimal[scenarios.size()])[column] = line.getAmount();
                states.computeIfAbsent(key, k -> new String[scenarios.size()])[column] = stateLabel(line.getPriceState());
            }
        }
        var rows = order.entrySet().stream().map(entry -> {
            var row = entry.getValue();
            return new ComparisonRow(row.category(), row.article(), row.details(), row.phaseLabel(),
                    row.kindLabel(), Arrays.asList(amounts.get(entry.getKey())),
                    Arrays.asList(states.get(entry.getKey())));
        }).toList();
        return new Comparison(event, scenarios.stream()
                .map(b -> new Scenario(b, zero(b.getTotal()))).toList(), rows, divergent[0]);
    }

    /** "Villa Balbiano v2" — how a scenario is named on a sheet tab and in a file name. */
    public static String label(EventBudget budget) {
        var scenario = budget.getScenario() == null || budget.getScenario().isBlank() ? "Estimate" : budget.getScenario();
        var revision = budget.getRevision() == null ? 1 : budget.getRevision();
        return revision > 1 ? scenario + " v" + revision : scenario;
    }

    /** A file name the client can recognise a week later: event, scenario, and the day it was cut. */
    public static String fileName(EventProject event, String suffix) {
        var name = event.getDescription() == null || event.getDescription().isBlank()
                ? event.getCode() : event.getDescription();
        var safe = (name + " " + suffix).replaceAll("[^A-Za-z0-9 ._-]", " ").replaceAll("\\s+", " ").trim();
        return LocalDate.now() + " " + safe + ".xlsx";
    }
}
