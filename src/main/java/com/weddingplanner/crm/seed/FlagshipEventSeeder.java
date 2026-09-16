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
import com.weddingplanner.crm.events.repository.EventInvoiceRepository;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import com.weddingplanner.crm.repository.ContactRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
     * A specific event to work up, for a deployment that wants one. Empty by default, because an
     * event id is derived from its couple and a couple is generated per database — an id pinned
     * here would name a real wedding on the machine it was written on and nothing at all anywhere
     * else, which is exactly how this seeder used to miss its target in the cloud.
     */
    private final String pinned;

    public FlagshipEventSeeder(EventProjectRepository events, EventBudgetRepository budgets,
            BudgetArticleRepository articles, EventPartyRepository parties, ContactRepository contacts,
            EventInvoiceRepository invoices,
            @org.springframework.beans.factory.annotation.Value("${planner.events.flagship:}") String pinned) {
        this.events = events;
        this.budgets = budgets;
        this.articles = articles;
        this.parties = parties;
        this.contacts = contacts;
        this.invoices = invoices;
        this.pinned = pinned;
    }

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
                    List.of("ceremony", "celebrant", "officiant", "legal", "registrar")),
            new Supplier("Fioraio Santamaria · Elena Conti", "elena@santamariafiori.it", "+39 091 555 0180",
                    InboxFolder.CONTRACTORS, "Ceremony, reception and table florals", "13",
                    List.of("flower", "floral", "bouquet", "centrepiece", "installation", "candle")),
            new Supplier("Studio Aureo · Paolo Ricci", "paolo@studioaureo.it", "+39 091 555 0181",
                    InboxFolder.CONTRACTORS, "Photography, film and the drone permit", "10",
                    List.of("photo", "video", "film", "drone", "album", "shooter")),
            new Supplier("Bottega Verde Rentals", "noleggio@bottegaverde.it", "+39 091 555 0182",
                    InboxFolder.CONTRACTORS, "Furniture, linen, tableware and the dance floor", "12",
                    List.of("furniture", "linen", "tableware", "china", "glassware", "dance floor",
                            "marquee", "tent", "rental")),
            new Supplier("Isola Guest Services", "welcome@isolaguest.it", "+39 091 555 0183",
                    InboxFolder.PARTNERS, "Guest welcome bags, concierge and room drops", "9",
                    List.of("gift", "favour", "welcome bag", "welcome pack", "hamper", "concierge")));

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final BudgetArticleRepository articles;
    private final EventPartyRepository parties;
    private final ContactRepository contacts;
    private final EventInvoiceRepository invoices;

    @Override
    public void run(String... args) {
        Optional<EventProject> target = flagship();
        if (target.isEmpty()) return;
        EventProject event = target.get();
        if (event.getSelectedBudget() == null) return;
        EventBudget selected = budgets.findActiveById(event.getSelectedBudget().id()).orElse(null);
        if (selected == null) return;
        // A wedding already billed is a wedding somebody has worked: its prices are what was agreed
        // with the suppliers who are invoicing against them, and re-pricing it here would move the
        // estimate out from under its own ledger. Scenarios and a roster are what this seeder adds
        // to an untouched event, not something to impose on a settled one.
        if (!invoices.findByEventAndDeletionMarkFalse(event.getId()).isEmpty()) return;

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
        // Both derived scenarios come from the estimate the book itself produced, never from each
        // other: trimming a trim makes each boot quietly cheaper than the last, and uplifting an
        // uplift makes it quietly dearer.
        UUID trimmedId = id("budget", "trimmed:" + event.getId());
        UUID enhancedId = id("budget", "enhanced:" + event.getId());
        EventBudget base = scenarios.stream()
                .filter(scenario -> !scenario.getId().equals(trimmedId))
                .filter(scenario -> !scenario.getId().equals(enhancedId))
                .findFirst().orElse(selected);
        alternative(event, base, random);
        enhanced(event, base, new Random(~event.getId().getMostSignificantBits()));

        // Signed, so the ledger settles it. A wedding with paid supplier invoices and no signature
        // would be the one incoherent row in the book.
        if (event.getStage() != EventStage.COMPLETED && event.getStage() != EventStage.CONFIRMED) {
            event.setStage(EventStage.CONFIRMED);
            events.save(event);
        }
    }

    /**
     * The event this demo is built around, for anything else that wants to enrich the same wedding.
     * Public so the conversation seeder can give its couple a chat history rather than leaving the
     * one event anybody opens as the one with nothing in its inbox.
     */
    public Optional<EventProject> flagship() {
        if (pinned != null && !pinned.isBlank()) {
            var chosen = events.findActiveById(UUID.fromString(pinned.trim()));
            if (chosen.isPresent()) return chosen;
        }
        // The event at the head of the Events list, which is the one anybody opens first: the list
        // is newest-created-first, and codes are issued in order, so the highest code is that row.
        // Choosing it by position rather than by id is what makes this seeder land on the same
        // wedding in every database — the ids differ, the top of the list does not.
        return events.findAllActive().stream()
                .filter(event -> event.getSelectedBudget() != null)
                .max(Comparator.comparing((EventProject event) -> Objects.toString(event.getCode(), ""))
                        .thenComparing(event -> budgets.findActiveById(event.getSelectedBudget().id())
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

    /** The rows a couple with room in the budget spends more on, and the only ones that move up. */
    private static final List<String> UPGRADES = List.of("flower", "floral", "bouquet", "centrepiece",
            "installation", "photo", "video", "film", "drone", "album", "band", "music", "live",
            "light", "sound", "production", "wine", "champagne", "menu", "catering", "cake");

    /**
     * The third scenario: the same wedding with the rows a couple brags about taken up a level —
     * a second shooter, the bigger band, florals on every table, wine off the reserve list.
     *
     * <p>Two scenarios make a comparison; three make a decision. A planner does not walk into the
     * meeting with the estimate and a cheaper version of it — the whole point of the middle option
     * is that it has something either side of it, and the couple picks the shape they recognise.</p>
     *
     * <p>Like the trim, it only moves the rows it is about. The venue, the celebrant and the coaches
     * cost what they cost, and a scenario that repriced them would be arithmetic rather than a
     * proposal.</p>
     */
    private void enhanced(EventProject event, EventBudget base, Random random) {
        UUID id = id("budget", "enhanced:" + event.getId());
        if (id.equals(base.getId())) return;
        var existing = budgets.findActiveById(id).orElse(null);
        if (existing != null && (existing.getScenario() == null || !existing.getScenario().endsWith("· enhanced"))) return;

        var budget = existing != null ? existing : new EventBudget();
        if (existing == null) {
            budget.setId(id);
            budget.setEvent(Ref.of(EventProject.class, event.getId()));
        }
        budget.getItems().clear();
        budget.setScenario(base.getScenario() + " · enhanced");
        budget.setRevision(3);
        budget.setPriceBasis("Same venue and guest count, feature rows taken up a level");
        for (EventBudgetLine source : base.getItems()) {
            var line = new EventBudgetLine();
            line.setPhase(source.getPhase());
            line.setArticle(source.getArticle());
            line.setDetails(source.getDetails());
            line.setKind(source.getKind());
            line.setPriceState(source.getPriceState());
            line.setQuantity(source.getQuantity());
            line.setContractor(source.getContractor());
            String article = articleName(source);
            boolean lift = !article.isBlank() && UPGRADES.stream().anyMatch(article::contains);
            if (source.getUnitPrice() != null) {
                BigDecimal factor = lift
                        ? BigDecimal.valueOf(1.18 + random.nextInt(22) / 100.0)
                        : BigDecimal.ONE;
                line.setUnitPrice(source.getUnitPrice().multiply(factor).setScale(2, RoundingMode.HALF_UP));
            }
            if (source.getUnitCost() != null) {
                // A better version of the same row costs us more too, but not proportionally: the
                // uplift is where the margin on a wedding is actually made.
                line.setUnitCost(lift
                        ? source.getUnitCost().multiply(new BigDecimal("1.12")).setScale(2, RoundingMode.HALF_UP)
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
