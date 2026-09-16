package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.BudgetArticle;
import com.weddingplanner.crm.events.domain.BudgetPriceState;
import com.weddingplanner.crm.events.domain.EventBudget;
import com.weddingplanner.crm.events.domain.EventBudgetLine;
import com.weddingplanner.crm.events.domain.EventParty;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.domain.EventStage;
import com.weddingplanner.crm.events.repository.BudgetArticleRepository;
import com.weddingplanner.crm.events.repository.EventBudgetRepository;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import com.weddingplanner.crm.repository.ContactRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

/**
 * One wedding worked all the way through, so the event screen has something to show.
 *
 * <p>The event book generates twenty weddings, and every one of them is thin in the same way: an
 * estimate with articles nobody has priced, three suppliers, and — because the money only moves once
 * an event is signed — a client panel, a supplier panel and a cash panel all reading €0. That is a
 * fair picture of a planning-stage wedding and a poor demo, because the tabs someone clicks first
 * (estimates, team, invoices, payments, margin) are exactly the empty ones.
 *
 * <p>So one event is taken all the way: a full supplier roster with commissions, every article
 * priced and owned by the contractor who will deliver it, our own cost against each so margin is a
 * real number, and a second scenario to choose between. Deliberately one and not twenty — a book of
 * twenty fully-settled weddings is a slower demo and a less believable one, since a real book always
 * has most of its weddings in front of it rather than behind.
 *
 * <p>It stops at the money. The event is moved to confirmed and the rest is left to
 * {@link EventLedgerSeeder}, which already knows how a signed event settles — 40% of the suppliers
 * committed, 30% of the client invoiced — so the invoices and payments here are produced by the same
 * code that produces them everywhere else, not by a second implementation that could disagree.
 */
@Order(30)
@Component
@ConditionalOnProperty(name = "planner.events.demo", havingValue = "true")
public class FlagshipEventSeeder implements CommandLineRunner {

    /**
     * The event the demo links to. Event ids are derived from the couple, so this one survives a
     * reseed of the same book; when the book changes underneath it, the biggest estimate is the
     * next best thing to put on screen.
     */
    private static final UUID LINKED_EVENT = UUID.fromString("1caefbea-624d-34aa-946c-e780a3103b6b");

    /** A supplier, and the estimate articles that are obviously theirs. */
    private record Supplier(String name, String email, String phone, InboxFolder role,
                            String scope, String commission, List<String> articles) {}

    private static final List<Supplier> ROSTER = List.of(
            new Supplier("Cucina Aurora · Giulia Ferri", "giulia@cucinaaurora.it", "+39 091 555 0170",
                    InboxFolder.CONTRACTORS, "Catering, service staff and the bar", "14",
                    List.of("catering", "food", "menu", "dinner", "lunch", "bar", "drink", "wine")),
            new Supplier("Orchestra Mediterranea", "info@orchestramed.it", "+39 091 555 0171",
                    InboxFolder.CONTRACTORS, "Live band for the reception", "10",
                    List.of("band", "music", "live", "entertain")),
            new Supplier("Nero DJ Collective", "book@nerodj.it", "+39 091 555 0172",
                    InboxFolder.CONTRACTORS, "DJ and late set", "10",
                    List.of("dj", "late", "after party", "afterparty")),
            new Supplier("Atelier Sole · Marta Greco", "marta@ateliersole.it", "+39 091 555 0173",
                    InboxFolder.CONTRACTORS, "Hair and make-up for the couple and party", "12",
                    List.of("hair", "make-up", "makeup", "beauty", "stylist")),
            new Supplier("Trinacria Transfers", "ops@trinacriatransfers.it", "+39 091 555 0174",
                    InboxFolder.CONTRACTORS, "Guest coaches and couple's car", "8",
                    List.of("transport", "transfer", "coach", "bus", "car", "shuttle")),
            new Supplier("Carta Bianca Stationery", "hello@cartabianca.it", "+39 091 555 0175",
                    InboxFolder.CONTRACTORS, "Invitations, menus and signage", "15",
                    List.of("stationery", "invitation", "print", "signage", "menu card", "calligraph")),
            new Supplier("Pasticceria Rosa", "ordini@pasticceriarosa.it", "+39 091 555 0176",
                    InboxFolder.CONTRACTORS, "Wedding cake and dessert table", "12",
                    List.of("cake", "dessert", "sweet", "patisserie")),
            new Supplier("Luce Scenica Productions", "studio@lucescenica.it", "+39 091 555 0177",
                    InboxFolder.CONTRACTORS, "Lighting, sound and staging", "11",
                    List.of("light", "sound", "stage", "production", "power", "generator")),
            new Supplier("Dimora Aurora Estate", "events@dimoraaurora.example", "+39 091 555 0178",
                    InboxFolder.VENUES, "Villa, gardens and guest rooms", "8",
                    List.of("venue", "villa", "rent", "location fee", "estate", "garden")),
            new Supplier("Rosa Marino · Celebrant", "rosa@marinocelebrant.it", "+39 091 555 0179",
                    InboxFolder.CONTRACTORS, "Ceremony and legal paperwork", "0",
                    List.of("ceremony", "celebrant", "officiant", "legal", "registrar")));

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final BudgetArticleRepository articles;
    private final EventPartyRepository parties;
    private final ContactRepository contacts;

    public FlagshipEventSeeder(EventProjectRepository events, EventBudgetRepository budgets,
            BudgetArticleRepository articles, EventPartyRepository parties, ContactRepository contacts) {
        this.events = events;
        this.budgets = budgets;
        this.articles = articles;
        this.parties = parties;
        this.contacts = contacts;
    }

    @Override
    public void run(String... args) {
        Optional<EventProject> target = flagship();
        if (target.isEmpty()) return;
        EventProject event = target.get();
        if (event.getSelectedBudget() == null) return;
        EventBudget selected = budgets.findActiveById(event.getSelectedBudget().id()).orElse(null);
        if (selected == null) return;

        var random = new Random(event.getId().getMostSignificantBits());
        for (Supplier supplier : ROSTER) party(event, supplier(supplier), supplier);

        // Every scenario on this event gets priced, not only the one driving it: an alternative full
        // of "to be confirmed" is not an alternative anyone can weigh.
        var scenarios = budgets.findAllActive().stream()
                .filter(budget -> budget.getEvent() != null && event.getId().equals(budget.getEvent().id()))
                .toList();
        for (EventBudget scenario : scenarios) {
            price(scenario, new Random(scenario.getId().getMostSignificantBits()));
        }
        // The alternative is derived from the full estimate, whichever one the planner has since
        // pointed the event at — deriving it from the trimmed scenario would trim a trim.
        UUID trimmedId = id("budget", "trimmed:" + event.getId());
        EventBudget base = scenarios.stream()
                .filter(scenario -> !scenario.getId().equals(trimmedId))
                .findFirst().orElse(selected);
        alternative(event, base, random);

        // Signed, so the ledger settles it. A wedding with paid supplier invoices and no signature
        // would be the one incoherent row in the book.
        if (event.getStage() != EventStage.COMPLETED && event.getStage() != EventStage.CONFIRMED) {
            event.setStage(EventStage.CONFIRMED);
            events.save(event);
        }
    }

    private Optional<EventProject> flagship() {
        var linked = events.findActiveById(LINKED_EVENT);
        if (linked.isPresent()) return linked;
        return events.findAllActive().stream()
                .filter(event -> event.getSelectedBudget() != null)
                .max(Comparator.comparing(event -> budgets.findActiveById(event.getSelectedBudget().id())
                        .map(EventBudget::getTotal).orElse(BigDecimal.ZERO)));
    }

    /**
     * Price every article and give it an owner. An estimate with eight articles reading "to be
     * confirmed" is the right shape for a wedding still being planned and the wrong one for the
     * event someone is being shown, where every panel downstream — committed, invoiced, margin —
     * reads zero because nothing above it carries a number.
     */
    private void price(EventBudget budget, Random random) {
        boolean changed = false;
        for (EventBudgetLine line : budget.getItems()) {
            String article = articleName(line);

            if (line.getContractor() == null && !article.isBlank()) {
                for (Supplier supplier : ROSTER) {
                    if (supplier.articles().stream().noneMatch(article::contains)) continue;
                    line.setContractor(Ref.of(Contact.class, id("contact", supplier.name())));
                    changed = true;
                    break;
                }
            }

            if (line.getPriceState() == BudgetPriceState.TBD || line.getUnitPrice() == null) {
                // A quote in the shape the rest of the book uses: round hundreds, sized to the
                // article rather than to a single constant that would price a cake like a villa.
                int unit = (2 + random.nextInt(18)) * 500;
                line.setPriceState(BudgetPriceState.QUOTED);
                line.setUnitPrice(BigDecimal.valueOf(unit));
                changed = true;
            }

            // What the row costs us, so the margin panel is answering from data rather than from a
            // blank column. A venue passes through at close to its price; a service carries more.
            if (line.getUnitCost() == null && line.getUnitPrice() != null) {
                BigDecimal share = BigDecimal.valueOf(0.62 + random.nextInt(24) / 100.0);
                line.setUnitCost(line.getUnitPrice().multiply(share).setScale(2, RoundingMode.HALF_UP));
                changed = true;
            }
        }
        if (changed) budgets.save(budget);
    }

    /** The rows a couple over budget is asked to give up first. Everything else is not negotiable. */
    private static final List<String> DISCRETIONARY = List.of("flower", "floral", "decor", "décor", "candle",
            "installation", "band", "music", "dj", "entertain", "firework", "light", "stage", "production",
            "cake", "dessert", "stationery", "invitation", "print", "favour", "hair", "make-up", "makeup",
            "transfer", "coach", "shuttle", "album", "drone");

    /**
     * A second scenario, because the page asks the planner to pick one and a list of one is not a
     * choice. The same wedding with the guest count held and the discretionary rows trimmed, which
     * is the comparison a couple over budget actually asks for.
     *
     * <p>Only those rows move. A trimmed scenario that repriced all fifty-three positions would be
     * arithmetic rather than a proposal — and it would leave the comparison with nothing to
     * highlight, since a view of "only what differs" is the whole estimate again.
     */
    private void alternative(EventProject event, EventBudget selected, Random random) {
        UUID id = id("budget", "trimmed:" + event.getId());
        // Someone may have pointed the event at the trimmed scenario, which would make this a trim
        // of a trim — each boot quietly cheaper than the last. The alternative is only ever derived
        // from the full estimate.
        if (id.equals(selected.getId())) return;
        // Wholly this seeder's own artifact, so it is rewritten rather than left at whatever an
        // earlier version of this code produced. Anything else on the event is never touched.
        var existing = budgets.findActiveById(id).orElse(null);
        if (existing != null && (existing.getScenario() == null || !existing.getScenario().endsWith("· trimmed"))) return;

        // Mutate the row that is there rather than building a fresh aggregate around its id: a new
        // entity with an id already on disk is an INSERT, and the duplicate key takes the whole
        // application down at boot rather than failing quietly here.
        var budget = existing != null ? existing : new EventBudget();
        if (existing == null) {
            budget.setId(id);
            budget.setEvent(Ref.of(EventProject.class, event.getId()));
        }
        budget.getItems().clear();
        budget.setScenario(selected.getScenario() + " · trimmed");
        budget.setRevision(2);
        budget.setPriceBasis("Same venue and guest count, discretionary rows reduced");
        for (EventBudgetLine source : selected.getItems()) {
            var line = new EventBudgetLine();
            line.setPhase(source.getPhase());
            line.setArticle(source.getArticle());
            line.setDetails(source.getDetails());
            line.setKind(source.getKind());
            line.setPriceState(source.getPriceState());
            line.setQuantity(source.getQuantity());
            line.setContractor(source.getContractor());
            String article = articleName(source);
            boolean trim = !article.isBlank() && DISCRETIONARY.stream().anyMatch(article::contains);
            if (source.getUnitPrice() != null) {
                BigDecimal cut = trim
                        ? BigDecimal.valueOf(0.62 + random.nextInt(20) / 100.0)
                        : BigDecimal.ONE;
                line.setUnitPrice(source.getUnitPrice().multiply(cut).setScale(2, RoundingMode.HALF_UP));
            }
            if (source.getUnitCost() != null) {
                line.setUnitCost(trim
                        ? source.getUnitCost().multiply(new BigDecimal("0.85")).setScale(2, RoundingMode.HALF_UP)
                        : source.getUnitCost());
            }
            budget.getItems().add(line);
        }
        budgets.save(budget);
    }

    private String articleName(EventBudgetLine line) {
        if (line.getArticle() == null) return "";
        return articles.findActiveById(line.getArticle().id())
                .map(BudgetArticle::getDescription).orElse("").toLowerCase(Locale.ROOT);
    }

    private Contact supplier(Supplier supplier) {
        UUID id = id("contact", supplier.name());
        var existing = contacts.findActiveById(id);
        if (existing.isPresent()) return existing.get();
        var contact = new Contact();
        contact.setId(id);
        contact.setDescription(supplier.name());
        contact.setEmail(supplier.email());
        contact.setPhone(supplier.phone());
        contact.setInboxFolder(supplier.role());
        contact.setCommissionRate(new BigDecimal(supplier.commission()));
        return contacts.save(contact);
    }

    private void party(EventProject event, Contact contact, Supplier supplier) {
        UUID id = id("party", event.getId() + ":" + contact.getId());
        if (parties.findActiveById(id).isPresent()) return;
        var party = new EventParty();
        party.setId(id);
        party.setEvent(Ref.of(EventProject.class, event.getId()));
        party.setContact(Ref.of(Contact.class, contact.getId()));
        party.setRole(supplier.role());
        party.setScope(supplier.scope());
        party.setCommissionRate(new BigDecimal(supplier.commission()));
        parties.save(party);
    }

    private static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("planner:flagship:v1:" + kind + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }
}
