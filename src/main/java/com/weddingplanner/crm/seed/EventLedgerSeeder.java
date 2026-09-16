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

    public EventLedgerSeeder(EventProjectRepository events, EventBudgetRepository budgets,
            EventInvoiceRepository invoices, EventPaymentRepository payments, PostingService posting) {
        this.events = events; this.budgets = budgets;
        this.invoices = invoices; this.payments = payments; this.posting = posting;
    }

    /** How far along an event's money is, which is a question about its stage and nothing else. */
    private record Settlement(boolean delivered, BigDecimal supplierShare, BigDecimal clientShare) {}

    private static final Settlement COMPLETED =
            new Settlement(true, BigDecimal.ONE, BigDecimal.ONE);
    private static final Settlement CONFIRMED =
            new Settlement(false, new BigDecimal("0.40"), new BigDecimal("0.30"));

    @Override
    public void run(String... args) {
        for (EventProject event : events.findAllActive()) {
            if (event.getSelectedBudget() == null) continue;
            Settlement settlement = switch (event.getStage()) {
                case COMPLETED -> COMPLETED;
                case CONFIRMED -> CONFIRMED;
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
        // A wedding still to come has only started committing: the venue and the first suppliers are
        // booked, the rest is quoted and nothing more.
        int commitments = settlement.delivered() ? byContractor.size()
                : Math.min(2, byContractor.size());
        int index = 0;
        for (var entry : byContractor.entrySet()) {
            if (index++ >= commitments) break;
            supplier(event, budget, entry.getKey(), entry.getValue(), settlement);
        }

        client(event, budget, quoted, settlement);
    }

    private void supplier(EventProject event, EventBudget budget, UUID contractor,
            List<EventBudgetLine> lines, Settlement settlement) {
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
        pay(id("supplier-payment", invoiceId.toString()), invoice,
                share(invoice.getTotal(), settlement.supplierShare()),
                settlement.delivered() ? "Bank transfer, final settlement" : "Bank transfer, deposit");
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
        pay(id("client-payment", invoiceId.toString()), invoice,
                share(invoice.getTotal(), settlement.clientShare()),
                settlement.delivered() ? "Received in full" : "Booking deposit received");
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
     * committed, so a document written and posted inside one transaction is rejected. Returns null
     * when the document already exists, which is what makes a restart a no-op.
     */
    private EventInvoice post(UUID id, java.util.function.Supplier<EventInvoice> build) {
        if (invoices.findById(id).isPresent()) return null;
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
