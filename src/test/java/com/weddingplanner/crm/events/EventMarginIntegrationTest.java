package com.weddingplanner.crm.events;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import su.onno.posting.PostingService;
import su.onno.types.Ref;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;
import com.weddingplanner.crm.events.service.EventMarginService;
import com.weddingplanner.crm.events.web.EventWorkspaceController;
import com.weddingplanner.crm.repository.ContactRepository;

import static org.assertj.core.api.Assertions.*;

/**
 * The internal margin. The interesting assertions are the ones about restraint: an estimate with no
 * costs and no commission rates must report zero margin rather than mistaking the whole wedding
 * budget for profit, and a refundable deposit must not earn anything at all.
 *
 * <p>The demo import is off here so every figure comes from data this test wrote. Margin is
 * narrowed to {@code ADMIN} so the role gate can be exercised against a manager who can read
 * everything else on the event.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:event-margin;DB_CLOSE_DELAY=-1",
        "planner.events.demo=false", "planner.marketing.demo-history=false", "planner.crm.demo-threads=false",
        "planner.events.margin-roles=ADMIN"})
class EventMarginIntegrationTest {

    @Autowired EventMarginService margins;
    @Autowired EventWorkspaceController workspace;
    @Autowired EventProjectRepository events;
    @Autowired EventBudgetRepository budgets;
    @Autowired EventInvoiceRepository invoices;
    @Autowired EventPartyRepository parties;
    @Autowired BudgetArticleRepository articles;
    @Autowired ContactRepository contacts;
    @Autowired PostingService posting;

    private static final UsernamePasswordAuthenticationToken MANAGER =
            new UsernamePasswordAuthenticationToken("demo@weddingplanner.local", "",
                    List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
    private static final UsernamePasswordAuthenticationToken ADMIN =
            new UsernamePasswordAuthenticationToken("boss@weddingplanner.local", "",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    private EventProject event(String name) {
        var event = new EventProject();
        event.setDescription(name);
        return events.save(event);
    }

    private Contact contact(String name, String commission) {
        var contact = new Contact();
        contact.setDescription(name);
        if (commission != null) contact.setCommissionRate(new BigDecimal(commission));
        return contacts.save(contact);
    }

    private Ref<BudgetArticle> article(String name) {
        var article = new BudgetArticle();
        article.setDescription(name);
        return Ref.of(BudgetArticle.class, articles.save(article).getId());
    }

    /** One estimate row: what the couple is quoted, what it costs us, who supplies it. */
    private EventBudgetLine line(String name, String price, String cost, ChargeKind kind, Contact supplier) {
        var line = new EventBudgetLine();
        line.setArticle(article(name));
        line.setKind(kind);
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal(price));
        if (cost != null) line.setUnitCost(new BigDecimal(cost));
        if (supplier != null) line.setContractor(Ref.of(Contact.class, supplier.getId()));
        return line;
    }

    private EventBudget estimate(EventProject event, EventBudgetLine... lines) {
        var budget = new EventBudget();
        budget.setEvent(Ref.of(EventProject.class, event.getId()));
        budget.setScenario("Villa");
        budget.getItems().addAll(List.of(lines));
        budgets.save(budget);
        var reloaded = events.findActiveById(event.getId()).orElseThrow();
        reloaded.setSelectedBudget(Ref.of(EventBudget.class, budget.getId()));
        events.save(reloaded);
        return budgets.findById(budget.getId()).orElseThrow();
    }

    private EventInvoice post(EventProject event, Contact party, InvoiceDirection direction,
            ChargeKind kind, String amount) {
        var invoice = new EventInvoice();
        invoice.setEvent(Ref.of(EventProject.class, event.getId()));
        invoice.setCounterparty(Ref.of(Contact.class, party.getId()));
        invoice.setDirection(direction);
        var line = new EventInvoiceLine();
        line.setArticle(article("Invoiced " + kind));
        line.setKind(kind);
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal(amount));
        invoice.getItems().add(line);
        invoices.save(invoice);
        var saved = invoices.findById(invoice.getId()).orElseThrow();
        posting.post(saved);
        return saved;
    }

    @Test
    void anEstimateWithNoCostsAndNoCommissionEarnsNothing() {
        var event = event("Pass-through wedding");
        var florist = contact("Pass-through florist", null);
        var budget = estimate(event, line("Flowers", "10000", null, ChargeKind.SERVICE, florist));
        // A blank cost column is a pass-through, not a 100% margin: the row costs what it is quoted.
        assertThat(budget.getCostTotal()).isEqualByComparingTo("10000");
        var margin = margins.margin(event.getId());
        assertThat(margin.forecastRevenue()).isEqualByComparingTo("10000");
        assertThat(margin.forecastCost()).isEqualByComparingTo("10000");
        assertThat(margin.forecastTotal()).isEqualByComparingTo("0");
        assertThat(margin.commissions()).isEmpty();
    }

    @Test
    void feeMarkupAndCommissionAreReportedApartAndAddUp() {
        var event = event("Three-source wedding");
        var florist = contact("Commissioning florist", "10");
        var budget = estimate(event,
                line("Planning fee", "25000", null, ChargeKind.AGENCY_FEE, null),
                line("Flowers", "12000", "9000", ChargeKind.SERVICE, florist));
        // The fee costs nothing; the florist's row costs what the florist charges.
        assertThat(budget.getCostTotal()).isEqualByComparingTo("9000");
        var margin = margins.margin(event.getId());
        assertThat(margin.forecastFee()).isEqualByComparingTo("25000");
        assertThat(margin.forecastMarkup()).isEqualByComparingTo("3000");
        // 10% of the 9,000 the florist is actually paid, not of the 12,000 the couple is quoted.
        assertThat(margin.forecastCommission()).isEqualByComparingTo("900");
        assertThat(margin.forecastTotal()).isEqualByComparingTo("28900");
        assertThat(margin.forecastRevenue()).isEqualByComparingTo("37000");
        assertThat(margin.forecastRate()).isEqualByComparingTo("78.1");
        assertThat(margin.commissions()).singleElement().satisfies(row -> {
            assertThat(row.contractor()).isEqualTo("Commissioning florist");
            assertThat(row.forecastBase()).isEqualByComparingTo("9000");
            assertThat(row.eventRate()).isFalse();
        });
    }

    @Test
    void theEventsOwnRateBeatsTheSuppliersStandingOne() {
        var event = event("Renegotiated wedding");
        var venue = contact("Standing-rate venue", "8");
        estimate(event, line("Villa rental", "100000", null, ChargeKind.SERVICE, venue));
        assertThat(margins.margin(event.getId()).forecastCommission()).isEqualByComparingTo("8000");

        var party = new EventParty();
        party.setEvent(Ref.of(EventProject.class, event.getId()));
        party.setContact(Ref.of(Contact.class, venue.getId()));
        party.setRole(InboxFolder.VENUES);
        party.setCommissionRate(new BigDecimal("15"));
        parties.save(party);

        var margin = margins.margin(event.getId());
        assertThat(margin.forecastCommission()).isEqualByComparingTo("15000");
        assertThat(margin.commissions()).singleElement()
                .satisfies(row -> assertThat(row.eventRate()).isTrue());
        // The venue's own card is untouched — the deal belongs to this event.
        assertThat(contacts.findActiveById(venue.getId()).orElseThrow().getCommissionRate())
                .isEqualByComparingTo("8");
    }

    @Test
    void refundableDepositsEarnNothingOnEitherSide() {
        var event = event("Deposit wedding");
        var venue = contact("Deposit venue", "10");
        estimate(event, line("Security deposit", "35000", null, ChargeKind.DEPOSIT, venue));
        var forecast = margins.margin(event.getId());
        assertThat(forecast.forecastRevenue()).isZero();
        assertThat(forecast.forecastTotal()).isZero();
        assertThat(forecast.commissions()).isEmpty();

        post(event, venue, InvoiceDirection.CLIENT, ChargeKind.DEPOSIT, "35000");
        post(event, venue, InvoiceDirection.SUPPLIER, ChargeKind.DEPOSIT, "35000");
        var booked = margins.margin(event.getId());
        assertThat(booked.bookedRevenue()).isZero();
        assertThat(booked.bookedCost()).isZero();
        assertThat(booked.bookedTotal()).isZero();
    }

    @Test
    void bookedCountsPostedInvoicesOnlyAndAdmitsWhenItIsIncomparable() {
        var event = event("Half-billed wedding");
        var florist = contact("Booked florist", "10");
        estimate(event,
                line("Planning fee", "20000", null, ChargeKind.AGENCY_FEE, null),
                line("Flowers", "10000", "8000", ChargeKind.SERVICE, florist));

        var draft = new EventInvoice();
        draft.setEvent(Ref.of(EventProject.class, event.getId()));
        draft.setCounterparty(Ref.of(Contact.class, florist.getId()));
        draft.setDirection(InvoiceDirection.SUPPLIER);
        var draftLine = new EventInvoiceLine();
        draftLine.setArticle(article("Unposted flowers"));
        draftLine.setQuantity(BigDecimal.ONE);
        draftLine.setUnitPrice(new BigDecimal("8000"));
        draft.getItems().add(draftLine);
        invoices.save(draft);
        // A draft supplier bill is not a commitment, so it changes nothing.
        assertThat(margins.margin(event.getId()).bookedCost()).isZero();

        post(event, florist, InvoiceDirection.CLIENT, ChargeKind.AGENCY_FEE, "20000");
        var half = margins.margin(event.getId());
        // Billing the couple before any supplier does reads as pure profit — which is exactly why
        // the panel refuses to call this the event's margin until both sides are invoiced.
        assertThat(half.bookedTotal()).isEqualByComparingTo("20000");
        assertThat(half.fullyInvoiced()).isFalse();

        post(event, florist, InvoiceDirection.CLIENT, ChargeKind.SERVICE, "10000");
        post(event, florist, InvoiceDirection.SUPPLIER, ChargeKind.SERVICE, "8000");
        var full = margins.margin(event.getId());
        assertThat(full.bookedFee()).isEqualByComparingTo("20000");
        assertThat(full.bookedMarkup()).isEqualByComparingTo("2000");
        assertThat(full.bookedCommission()).isEqualByComparingTo("800");
        assertThat(full.bookedTotal()).isEqualByComparingTo("22800");
        assertThat(full.fullyInvoiced()).isTrue();
    }

    @Test
    void marginIsGatedSeparatelyFromTheRestOfTheEvent() {
        var event = event("Gated wedding");
        estimate(event, line("Flowers", "1000", null, ChargeKind.SERVICE, null));
        UUID id = event.getId();
        // The manager runs the event and sees every other figure on the page.
        assertThat(workspace.workspace(id, null, MANAGER)).containsEntry("canSeeMargin", false);
        assertThatThrownBy(() -> workspace.margin(id, MANAGER))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(workspace.workspace(id, null, ADMIN)).containsEntry("canSeeMargin", true);
        assertThat(workspace.margin(id, ADMIN).hasEstimate()).isTrue();
        assertThatThrownBy(() -> workspace.margin(id, null)).isInstanceOf(ResponseStatusException.class);
    }
}
