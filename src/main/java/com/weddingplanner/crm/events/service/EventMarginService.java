package com.weddingplanner.crm.events.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import su.onno.types.Ref;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;
import com.weddingplanner.crm.repository.ContactRepository;

/**
 * What Wedding Planner actually earns on an event.
 *
 * <p>The couple sees one number — what the wedding costs. Wedding Planner earns from three places behind it,
 * and they are genuinely different things, so this reports them apart rather than as one blended
 * margin:</p>
 *
 * <ol>
 *   <li><b>Agency fee</b> — the planning fee charged in the open, on the estimate and on the
 *       client's invoice. Disclosed, and the only part the couple can see.</li>
 *   <li><b>Markup</b> — the gap between what a supplier's article is quoted at and what that
 *       supplier actually charges Wedding Planner for it. Only exists where somebody has entered the cost;
 *       a blank cost is read as a pass-through, never as a markup.</li>
 *   <li><b>Commission</b> — the share a supplier rebates to Wedding Planner out of their own invoice, at the
 *       rate on the event's participant row or, failing that, on their contact card. It appears on
 *       no client document at all.</li>
 * </ol>
 *
 * <p>Every figure is reported twice. <b>Forecast</b> reads the selected estimate: what the event
 * will earn if it runs as quoted, which is the number worth planning against. <b>Booked</b> reads
 * only posted invoices: what has actually been committed on both sides so far. They disagree for
 * most of an event's life, because client invoicing and supplier invoicing run on different
 * schedules — {@link Margin#fullyInvoiced()} says when the booked column is finally comparable.</p>
 *
 * <p>Refundable deposits are excluded from both columns and from every commission base. Money held
 * and given back is not revenue, is not cost, and nobody pays commission on it.</p>
 */
@Service
public class EventMarginService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final EventInvoiceRepository invoices;
    private final EventPartyRepository parties;
    private final ContactRepository contacts;

    public EventMarginService(EventProjectRepository events, EventBudgetRepository budgets,
            EventInvoiceRepository invoices, EventPartyRepository parties, ContactRepository contacts) {
        this.events = events; this.budgets = budgets; this.invoices = invoices;
        this.parties = parties; this.contacts = contacts;
    }

    /** One supplier's rebate: the rate that applies, what it is charged on, and what it is worth. */
    public record Commission(String contractor, BigDecimal rate, BigDecimal forecastBase,
            BigDecimal forecast, BigDecimal bookedBase, BigDecimal booked, boolean eventRate) {}

    /**
     * @param forecastRevenue   priced, non-deposit rows of the selected estimate
     * @param forecastCost      what those rows cost Wedding Planner
     * @param forecastUnpriced  rows still without a price, so the forecast is known to be partial
     * @param bookedRevenue     posted client invoices, deposits excluded
     * @param bookedCost        posted supplier invoices, deposits excluded
     * @param fullyInvoiced     both sides are invoiced at least to the estimate, so booked is comparable
     */
    public record Margin(
            BigDecimal forecastRevenue, BigDecimal forecastCost, BigDecimal forecastFee,
            BigDecimal forecastMarkup, BigDecimal forecastCommission, BigDecimal forecastTotal,
            BigDecimal forecastRate, int forecastUnpriced, boolean hasEstimate,
            BigDecimal bookedRevenue, BigDecimal bookedCost, BigDecimal bookedFee,
            BigDecimal bookedMarkup, BigDecimal bookedCommission, BigDecimal bookedTotal,
            BigDecimal bookedRate, boolean fullyInvoiced,
            List<Commission> commissions) {}

    @Transactional(readOnly = true)
    public Margin margin(UUID eventId) {
        var event = events.findActiveById(eventId).orElseThrow();
        var estimate = event.getSelectedBudget() == null ? null
                : budgets.findActiveById(event.getSelectedBudget().id()).orElse(null);
        var posted = invoices.findAllActive().stream()
                .filter(i -> i.isPosted() && i.getEvent() != null && eventId.equals(i.getEvent().id()))
                .toList();

        // Per-contractor commission bases, forecast and booked, accumulated side by side so one
        // supplier is one row whether they appear in the estimate, on an invoice, or on both.
        var forecastBase = new LinkedHashMap<UUID, BigDecimal>();
        var bookedBase = new LinkedHashMap<UUID, BigDecimal>();

        BigDecimal forecastRevenue = BigDecimal.ZERO, forecastCost = BigDecimal.ZERO;
        BigDecimal forecastFee = BigDecimal.ZERO, forecastMarkup = BigDecimal.ZERO;
        int unpriced = 0;
        if (estimate != null) {
            unpriced = estimate.getUnknownItems() == null ? 0 : estimate.getUnknownItems();
            for (var line : estimate.getItems()) {
                if (line.getAmount() == null || line.getKind() == ChargeKind.DEPOSIT) continue;
                var amount = line.getAmount();
                var cost = line.getCostAmount() == null ? BigDecimal.ZERO : line.getCostAmount();
                forecastRevenue = forecastRevenue.add(amount);
                forecastCost = forecastCost.add(cost);
                if (line.getKind() == ChargeKind.AGENCY_FEE) forecastFee = forecastFee.add(amount.subtract(cost));
                else forecastMarkup = forecastMarkup.add(amount.subtract(cost));
                // Commission is paid on what the supplier is paid, not on what the couple is quoted.
                if (line.getContractor() != null) forecastBase.merge(line.getContractor().id(), cost, BigDecimal::add);
            }
        }

        BigDecimal bookedRevenue = BigDecimal.ZERO, bookedCost = BigDecimal.ZERO;
        BigDecimal bookedFee = BigDecimal.ZERO, bookedMarkup = BigDecimal.ZERO;
        for (var invoice : posted) {
            var client = invoice.getDirection() == InvoiceDirection.CLIENT;
            for (var line : invoice.getItems()) {
                if (line.getKind() == ChargeKind.DEPOSIT) continue;
                var amount = line.getAmount() == null ? BigDecimal.ZERO : line.getAmount();
                if (client) {
                    bookedRevenue = bookedRevenue.add(amount);
                    if (line.getKind() == ChargeKind.AGENCY_FEE) bookedFee = bookedFee.add(amount);
                    else bookedMarkup = bookedMarkup.add(amount);
                } else {
                    // A partner agency's fee is somebody else's revenue and Wedding Planner's cost, so a
                    // supplier-side fee line is cost like any other.
                    bookedCost = bookedCost.add(amount);
                    bookedMarkup = bookedMarkup.subtract(amount);
                    if (invoice.getCounterparty() != null)
                        bookedBase.merge(invoice.getCounterparty().id(), amount, BigDecimal::add);
                }
            }
        }

        var rows = new ArrayList<Commission>();
        BigDecimal forecastCommission = BigDecimal.ZERO, bookedCommission = BigDecimal.ZERO;
        var suppliers = new java.util.LinkedHashSet<UUID>();
        suppliers.addAll(forecastBase.keySet());
        suppliers.addAll(bookedBase.keySet());
        for (var supplier : suppliers) {
            var override = rate(eventId, supplier);
            var rate = override == null ? standingRate(supplier) : override;
            if (rate == null || rate.signum() <= 0) continue;
            var forecastOn = forecastBase.getOrDefault(supplier, BigDecimal.ZERO);
            var bookedOn = bookedBase.getOrDefault(supplier, BigDecimal.ZERO);
            var earnedForecast = share(forecastOn, rate);
            var earnedBooked = share(bookedOn, rate);
            forecastCommission = forecastCommission.add(earnedForecast);
            bookedCommission = bookedCommission.add(earnedBooked);
            rows.add(new Commission(name(supplier), rate, forecastOn, earnedForecast,
                    bookedOn, earnedBooked, override != null));
        }
        rows.sort((a, b) -> b.forecast().compareTo(a.forecast()));

        var forecastTotal = forecastFee.add(forecastMarkup).add(forecastCommission);
        var bookedTotal = bookedFee.add(bookedMarkup).add(bookedCommission);
        // Booked margin only means anything once both sides have been billed in full: until then a
        // client invoice with no supplier bills behind it reads as pure profit, and the reverse
        // reads as a loss. Comparing each side against the estimate is the cheapest honest test.
        var complete = estimate != null && forecastRevenue.signum() > 0
                && bookedRevenue.compareTo(forecastRevenue) >= 0 && bookedCost.compareTo(forecastCost) >= 0;
        return new Margin(
                forecastRevenue, forecastCost, forecastFee, forecastMarkup, forecastCommission,
                forecastTotal, percent(forecastTotal, forecastRevenue), unpriced, estimate != null,
                bookedRevenue, bookedCost, bookedFee, bookedMarkup, bookedCommission, bookedTotal,
                percent(bookedTotal, bookedRevenue), complete, rows);
    }

    /** The rate this event agreed with the supplier, or null when it uses their standing terms. */
    private BigDecimal rate(UUID event, UUID contact) {
        return parties.findAllActive().stream()
                .filter(p -> p.getEvent() != null && event.equals(p.getEvent().id()))
                .filter(p -> p.getContact() != null && contact.equals(p.getContact().id()))
                .map(EventParty::getCommissionRate).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private BigDecimal standingRate(UUID contact) {
        return contacts.findActiveById(contact).map(Contact::getCommissionRate).orElse(null);
    }

    private String name(UUID contact) {
        return contacts.findActiveById(contact).map(Contact::getDescription).orElse("Unavailable contact");
    }

    private static BigDecimal share(BigDecimal base, BigDecimal rate) {
        return base.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? BigDecimal.ZERO
                : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** Convenience for callers holding a typed reference rather than a raw id. */
    public Margin margin(Ref<EventProject> event) {
        return margin(event.id());
    }
}
