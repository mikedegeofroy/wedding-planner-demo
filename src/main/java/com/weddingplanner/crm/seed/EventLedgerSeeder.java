package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.EventBudgetRepository;
import com.weddingplanner.crm.events.repository.EventInvoiceRepository;
import com.weddingplanner.crm.events.repository.EventPaymentRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.posting.PostingService;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The money actually moved on the weddings in the book.
 *
 * <p>An estimate on its own says what a wedding should cost. What the event page is really asked is
 * how much of it is spoken for and how much has left the account — and that question has no answer
 * until somebody has raised an invoice. A wedding already delivered is invoiced and settled on both
 * sides; a wedding this season has its venue and its key suppliers committed with deposits paid;
 * one still being planned has nothing, which is the honest state of a wedding still being planned.
 *
 * <p>Every document is posted, because a draft moves nothing. Ids are stable and an existing
 * document is never rewritten, so a restart neither doubles the ledger nor undoes a demo edit.
 */
// After EventCrewSeeder(30) puts contractors behind the estimate lines: an article with no owner
// cannot be committed to anybody.
@Order(35)
@ConditionalOnProperty(name = "planner.events.demo", havingValue = "true")
@Component
public class EventLedgerSeeder implements CommandLineRunner {

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final EventInvoiceRepository invoices;
    private final EventPaymentRepository payments;
    private final PostingService posting;
    private final org.springframework.beans.factory.ObjectProvider<FlagshipEventSeeder> flagship;

    public EventLedgerSeeder(EventProjectRepository events, EventBudgetRepository budgets,
            EventInvoiceRepository invoices, EventPaymentRepository payments, PostingService posting,
            org.springframework.beans.factory.ObjectProvider<FlagshipEventSeeder> flagship) {
        this.events = events; this.budgets = budgets;
        this.invoices = invoices; this.payments = payments; this.posting = posting;
        this.flagship = flagship;
    }

    /** One transfer against an invoice: what it is called, and how much of the invoice it clears. */
    private record Instalment(String key, String reference, BigDecimal share) {}

    /**
     * How far along an event's money is. Usually a question about its stage and nothing else — the
     * exception is the wedding the demo is built around, which is worked far enough that the
     * payments tab has a history rather than a single line.
     */
    private record Settlement(boolean delivered, BigDecimal supplierShare, BigDecimal clientShare,
            boolean commitAll, List<Instalment> clientSchedule, List<Instalment> supplierSchedule) {
        Settlement(boolean delivered, BigDecimal supplierShare, BigDecimal clientShare) {
            this(delivered, supplierShare, clientShare, false, List.of(), List.of());
        }
    }

    private static final Settlement COMPLETED =
            new Settlement(true, BigDecimal.ONE, BigDecimal.ONE);
    private static final Settlement CONFIRMED =
            new Settlement(false, new BigDecimal("0.40"), new BigDecimal("0.30"));

    /**
     * The flagship: signed a while ago and paid into ever since. Every supplier is under contract
     * rather than the first two, the couple has made three transfers against their package and
     * still owes the balance, and the suppliers are part-paid at staggered depths — which is what
     * an event's money actually looks like a year out, and what the payments tab is there to show.
     */
    private static final Settlement FLAGSHIP = new Settlement(false,
            new BigDecimal("0.40"), new BigDecimal("0.35"), true,
            List.of(new Instalment("deposit", "Booking deposit received", new BigDecimal("0.15")),
                    new Instalment("second", "Second instalment received", new BigDecimal("0.12")),
                    new Instalment("third", "Third instalment received", new BigDecimal("0.08"))),
            List.of(new Instalment("deposit", "Bank transfer, deposit", new BigDecimal("0.40")),
                    new Instalment("balance", "Bank transfer, second instalment", new BigDecimal("0.25"))));

    @Override
    public void run(String... args) {
        var seeder = flagship.getIfAvailable();
        UUID featured = seeder == null ? null
                : seeder.flagship().map(EventProject::getId).orElse(null);
        for (EventProject event : events.findAllActive()) {
            if (event.getSelectedBudget() == null) continue;
            Settlement settlement = switch (event.getStage()) {
                case COMPLETED -> COMPLETED;
                case CONFIRMED -> event.getId().equals(featured) ? FLAGSHIP : CONFIRMED;
                case null, default -> null;
            };
            if (settlement == null) continue;
            budgets.findActiveById(event.getSelectedBudget().id())
                    .ifPresent(budget -> settle(event, budget, settlement));
        }
    }

    private void settle(EventProject event, EventBudget budget, Settlement settlement) {
        var quoted = budget.getItems().stream()
                .filter(line -> line.getPriceState() == BudgetPriceState.QUOTED)
                .filter(line -> line.getAmount() != null && line.getAmount().signum() > 0)
                .toList();
        if (quoted.isEmpty()) return;

        // Suppliers, one invoice each: a contractor bills for their own articles, not for the
        // wedding. Grouping by contractor is what makes the breakdown's committed column trace back
        // to a counterparty rather than to a category.
        var byContractor = new LinkedHashMap<UUID, List<EventBudgetLine>>();
        for (var line : quoted) {
            if (line.getContractor() == null) continue;
            byContractor.computeIfAbsent(line.getContractor().id(), key -> new ArrayList<>()).add(line);
        }

        // An event somebody has already billed by hand is not this seeder's to settle. Its own
        // documents are keyed, so re-running over them is a no-op — but a ledger raised anywhere
        // else carries different ids, and settling alongside it would bill the wedding twice.
        var mine = new HashSet<UUID>();
        mine.add(id("client", event.getId().toString()));
        for (UUID contractor : byContractor.keySet()) mine.add(id("supplier", event.getId() + ":" + contractor));
        boolean billedElsewhere = invoices.findByEventAndDeletionMarkFalse(event.getId()).stream()
                .anyMatch(invoice -> !mine.contains(invoice.getId()));
        if (billedElsewhere) return;
        // A wedding still to come has only started committing: the venue and the first suppliers are
        // booked, the rest is quoted and nothing more.
        int commitments = settlement.delivered() || settlement.commitAll() ? byContractor.size()
                : Math.min(2, byContractor.size());
        int index = 0;
        for (var entry : byContractor.entrySet()) {
            if (index >= commitments) break;
            supplier(event, budget, entry.getKey(), entry.getValue(), settlement, index);
            index++;
        }

        client(event, budget, quoted, settlement);
    }

    private void supplier(EventProject event, EventBudget budget, UUID contractor,
            List<EventBudgetLine> lines, Settlement settlement, int position) {
        UUID invoiceId = id("supplier", event.getId() + ":" + contractor);
        var invoice = post(invoiceId, () -> {
            var value = new EventInvoice();
            value.setId(invoiceId);
            value.setEvent(Ref.of(EventProject.class, event.getId()));
            value.setCounterparty(Ref.of(Contact.class, contractor));
            value.setDirection(InvoiceDirection.SUPPLIER);
            value.setNotes("Committed from estimate " + budget.getNumber() + " / " + budget.getScenario() + ".");
            for (var line : lines) value.getItems().add(copy(line));
            return value;
        });
        if (invoice == null) return;
        if (settlement.supplierSchedule().isEmpty()) {
            pay(id("supplier-payment", invoiceId.toString()), invoice,
                    share(invoice.getTotal(), settlement.supplierShare()),
                    settlement.delivered() ? "Bank transfer, final settlement" : "Bank transfer, deposit");
            return;
        }
        // Not every supplier is paid to the same depth. A deposit goes out when the contract is
        // signed and the next instalment when that supplier's own milestone lands, so at any given
        // moment half the roster is a payment ahead of the other half — which is the thing a
        // payments tab is read for, and a roster paid uniformly would hide.
        int instalments = position % 2 == 0 ? settlement.supplierSchedule().size() : 1;
        for (int i = 0; i < instalments; i++) {
            var instalment = settlement.supplierSchedule().get(i);
            pay(id("supplier-payment:" + instalment.key(), invoiceId.toString()), invoice,
                    share(invoice.getTotal(), instalment.share()), instalment.reference());
        }
    }

    /**
     * The couple's own invoice: the wedding as they were quoted it. One document for the whole
     * package, settled in full once the wedding has happened and in part before it has.
     */
    private void client(EventProject event, EventBudget budget, List<EventBudgetLine> quoted,
            Settlement settlement) {
        if (event.getClient() == null) return;
        UUID invoiceId = id("client", event.getId().toString());
        var invoice = post(invoiceId, () -> {
            var value = new EventInvoice();
            value.setId(invoiceId);
            value.setEvent(Ref.of(EventProject.class, event.getId()));
            value.setCounterparty(event.getClient());
            value.setDirection(InvoiceDirection.CLIENT);
            value.setNotes("Wedding package per estimate " + budget.getNumber() + " / " + budget.getScenario() + ".");
            for (var line : quoted) value.getItems().add(copy(line));
            return value;
        });
        if (invoice == null) return;
        if (settlement.clientSchedule().isEmpty()) {
            pay(id("client-payment", invoiceId.toString()), invoice,
                    share(invoice.getTotal(), settlement.clientShare()),
                    settlement.delivered() ? "Received in full" : "Booking deposit received");
            return;
        }
        // A couple pays a wedding off in stages against one contract, so the balance owed is the
        // number the client panel is really answering — and a single deposit line cannot show it
        // shrinking.
        for (var instalment : settlement.clientSchedule()) {
            pay(id("client-payment:" + instalment.key(), invoiceId.toString()), invoice,
                    share(invoice.getTotal(), instalment.share()), instalment.reference());
        }
    }

    private static EventInvoiceLine copy(EventBudgetLine line) {
        var item = new EventInvoiceLine();
        item.setArticle(line.getArticle());
        item.setPhase(line.getPhase());
        item.setKind(line.getKind() == null ? ChargeKind.SERVICE : line.getKind());
        item.setDetails(line.getDetails() == null ? null
                : line.getDetails().substring(0, Math.min(3000, line.getDetails().length())));
        item.setQuantity(line.getQuantity());
        item.setUnitPrice(line.getUnitPrice());
        return item;
    }

    /**
     * Save then post, as two transactions: the posting engine claims a row that is already
     * committed, so a document written and posted inside one transaction is rejected.
     *
     * <p>An invoice an earlier boot already posted is handed back rather than rebuilt, so payments
     * still settle against it — a schedule added to this seeder after the invoice was written would
     * otherwise never reach the events that already had one. Nothing is rewritten either way: the
     * document is left exactly as it stands, and the payments are keyed and skip what exists. An
     * invoice somebody deleted in the demo stays deleted and takes its payments with it.
     */
    private EventInvoice post(UUID id, java.util.function.Supplier<EventInvoice> build) {
        if (invoices.findById(id).isPresent()) return invoices.findActiveById(id).orElse(null);
        var invoice = build.get();
        if (invoice.getItems().isEmpty()) return null;
        invoices.save(invoice);
        var saved = invoices.findActiveById(id).orElse(null);
        if (saved == null || saved.getTotal() == null || saved.getTotal().signum() <= 0) return null;
        posting.post(saved);
        return invoices.findActiveById(id).orElse(null);
    }

    private void pay(UUID id, EventInvoice invoice, BigDecimal amount, String reference) {
        if (amount.signum() <= 0 || payments.findById(id).isPresent()) return;
        // Never transfer more than the invoice still has outstanding. An event settled by an older
        // version of this seeder already carries payments of its own, and posting a schedule on top
        // of them takes the invoice register negative — which fails the whole boot rather than the
        // one payment. What is left after the earlier boots is what this one pays.
        BigDecimal settled = payments.findByInvoiceAndDeletionMarkFalse(invoice.getId()).stream()
                .map(EventPayment::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = invoice.getTotal().subtract(settled);
        if (outstanding.signum() <= 0) return;
        amount = amount.min(outstanding);
        var payment = new EventPayment();
        payment.setId(id);
        payment.setInvoice(Ref.of(EventInvoice.class, invoice.getId()));
        payment.setAmount(amount);
        payment.setReference(reference);
        payments.save(payment);
        payments.findActiveById(id).ifPresent(posting::post);
    }

    /** A share of an invoice, never more than the invoice: an overpayment is rejected on posting. */
    private static BigDecimal share(BigDecimal total, BigDecimal fraction) {
        if (total == null) return BigDecimal.ZERO;
        return total.multiply(fraction).setScale(2, RoundingMode.HALF_UP).min(total);
    }

    private static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("planner:ledger:v1:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
