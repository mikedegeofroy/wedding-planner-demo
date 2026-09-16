package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.EventParty;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import com.weddingplanner.crm.repository.ContactRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

import java.util.List;
import java.util.UUID;

/**
 * Wedding Planner's own people, as contact cards in the Team folder.
 *
 * <p>The demo staffed every event with the couple and the suppliers and no one from the agency, so
 * the Team folder held nothing at all — and a participant picker narrowed to it came up empty, which
 * reads as a broken picker rather than as an empty folder. The planners are ordinary contacts: they
 * carry a card, join events as participants, and are reachable in the inbox like anyone else.</p>
 */
// After EventCrewSeeder(30): the couple and the suppliers are on the event by the time the planner
// who runs it joins them, so the Team tab reads in the order the wedding was actually staffed.
@Order(31)
@Component
public class PlannerTeamSeeder implements CommandLineRunner {

    private record Planner(String name, String email, String phone, String scope) {}

    private static final List<Planner> TEAM = List.of(
            new Planner("Giulia Moretti", "giulia@weddingplanner.example", "+39 02 555 0301",
                    "Lead planner · owns the wedding end to end"),
            new Planner("Tom Arkwright", "tom@weddingplanner.example", "+44 20 5550 0188",
                    "Producer · schedule, suppliers and the run of day"),
            new Planner("Sara Neri", "sara@weddingplanner.example", "+39 02 555 0344",
                    "Design lead · look, florals and stationery"),
            new Planner("Luca Ferrari", "luca@weddingplanner.example", "+39 02 555 0377",
                    "On-site coordinator · the weekend itself"),
            new Planner("Ana Petrova", "ana@weddingplanner.example", "+39 02 555 0392",
                    "Client services · contracts, invoices and payments"));

    private final ContactRepository contacts;
    private final EventProjectRepository events;
    private final EventPartyRepository parties;
    private final org.springframework.beans.factory.ObjectProvider<FlagshipEventSeeder> flagship;

    public PlannerTeamSeeder(ContactRepository contacts, EventProjectRepository events,
            EventPartyRepository parties,
            org.springframework.beans.factory.ObjectProvider<FlagshipEventSeeder> flagship) {
        this.contacts = contacts;
        this.events = events;
        this.parties = parties;
        this.flagship = flagship;
    }

    @Override
    public void run(String... args) {
        var roster = TEAM.stream().map(this::card).toList();
        var projects = events.findAllActive();
        // One planner owns each wedding, taken in turn so the demo's twenty events spread across the
        // agency instead of reading as one overworked person. Nothing here runs when the events side
        // of the demo is switched off: there are no events to staff.
        for (int i = 0; i < projects.size(); i++) {
            int seat = i % TEAM.size();
            join(projects.get(i), roster.get(seat).getId(), TEAM.get(seat).scope());
        }

        // The wedding the demo is built around gets the whole agency, which is what a wedding of
        // that size actually takes: one planner owning it, a producer, a designer, someone on site
        // and someone chasing the money. Everywhere else one name is right — a book where every
        // wedding is all-hands describes an agency with one client.
        var seeder = flagship.getIfAvailable();
        if (seeder == null) return;
        seeder.flagship().ifPresent(event -> {
            for (int seat = 0; seat < TEAM.size(); seat++) {
                join(event, roster.get(seat).getId(), TEAM.get(seat).scope());
            }
        });
    }

    /** One card per planner, keyed by name so a restart neither duplicates nor overwrites edits. */
    private Contact card(Planner planner) {
        UUID id = EventCrewSeeder.stableId("contact", planner.name());
        var existing = contacts.findActiveById(id);
        if (existing.isPresent()) return existing.get();
        var contact = new Contact();
        contact.setId(id);
        contact.setDescription(planner.name());
        contact.setEmail(planner.email());
        contact.setPhone(planner.phone());
        contact.setInboxFolder(InboxFolder.TEAM);
        return contacts.save(contact);
    }

    /** The same party id the crew seeder writes, so one person joins an event only once. */
    private void join(EventProject event, UUID contact, String scope) {
        UUID id = EventCrewSeeder.stableId("party", event.getId() + ":" + contact);
        if (parties.findActiveById(id).isPresent()) return;
        var party = new EventParty();
        party.setId(id);
        party.setEvent(Ref.of(EventProject.class, event.getId()));
        party.setContact(Ref.of(Contact.class, contact));
        party.setRole(InboxFolder.TEAM);
        party.setScope(scope);
        parties.save(party);
    }
}
