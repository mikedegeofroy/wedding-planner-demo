package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.events.domain.EventParty;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import com.weddingplanner.crm.repository.ContactRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.types.Ref;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The group chat every wedding actually runs in.
 *
 * <p>Suppliers write to the planner one to one — that is what {@link EventCrewSeeder} seeds — but
 * the day itself is coordinated in one WhatsApp group per wedding, named after the couple, holding
 * the couple, the planner and the suppliers working that date. Without one the demo inbox shows a
 * business that never puts its client and its crew in the same room, which is not how any of these
 * weddings are run.</p>
 *
 * <p>The group's members are the event's own party rows, so it names the people actually staffed on
 * that wedding rather than a fixed cast, and the thread reads as a group because its messages carry
 * their own authors — the CRM's conversation is one thread with many voices, not one counterparty.</p>
 */
// After EventCrewSeeder(30): the group is built from the party rows that seeder creates.
@org.springframework.core.annotation.Order(40)
@Component
public class EventGroupChatSeeder implements CommandLineRunner {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final EventProjectRepository events;
    private final EventPartyRepository parties;
    private final ContactRepository contacts;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public EventGroupChatSeeder(EventProjectRepository events, EventPartyRepository parties,
            ContactRepository contacts, InboxRepository inboxes, ConversationRepository conversations,
            ConversationMessageRepository messages) {
        this.events = events; this.parties = parties; this.contacts = contacts;
        this.inboxes = inboxes; this.conversations = conversations; this.messages = messages;
    }

    @Override
    public void run(String... args) {
        var projects = events.findAllActive();
        if (projects.isEmpty()) return;
        var party = parties.findAllActive();
        for (EventProject event : projects) {
            var members = party.stream()
                    .filter(row -> row.getEvent() != null && event.getId().equals(row.getEvent().id()))
                    .toList();
            var couple = named(members, InboxFolder.CLIENTS);
            // A wedding with nobody on it yet has no group to hold; the planner opens one when the
            // first supplier is booked, and so does this.
            if (couple.isEmpty() || members.size() < 2) continue;
            group(event, couple, named(members, InboxFolder.CONTRACTORS), named(members, InboxFolder.VENUES),
                    members.stream().filter(row -> row.getRole() == InboxFolder.CLIENTS)
                            .map(row -> row.getContact().id()).findFirst().orElseThrow());
        }
    }

    /** The people on this event in a given role, by the name their contact card carries. */
    private List<String> named(List<EventParty> members, InboxFolder role) {
        var names = new ArrayList<String>();
        for (EventParty member : members) {
            if (member.getRole() != role || member.getContact() == null) continue;
            contacts.findActiveById(member.getContact().id()).map(Contact::getDescription)
                    .filter(name -> name != null && !name.isBlank())
                    .ifPresent(names::add);
        }
        return names;
    }

    /**
     * One group per wedding, keyed by the event so a restart neither duplicates it nor overwrites
     * what the demo has since said in it.
     */
    private void group(EventProject event, List<String> couple, List<String> crew, List<String> venues,
            UUID owner) {
        UUID conversationId = stableId("conversation", event.getId().toString());

        // The same WhatsApp account the crew writes from, so the inbox does not grow a second
        // identical-looking one in the account picker.
        UUID inboxId = EventCrewSeeder.stableId("inbox", Channel.WHATSAPP);
        Inbox inbox = inboxes.findActiveById(inboxId).orElseGet(() -> {
            var value = new Inbox();
            value.setId(inboxId);
            value.setDescription("Planner WhatsApp");
            value.setChannel(Channel.WHATSAPP);
            value.setAddress("planner-groups:whatsapp");
            return inboxes.save(value);
        });

        String couples = String.join(" & ", couple);
        // Where the wedding is, as people say it — the place before the supplier's company name.
        String place = blankToNull(event.getLocation());
        String venue = venues.isEmpty() ? place : venues.getFirst();
        LocalDate date = event.getStartDate();
        var script = script(couple, crew, venues, event, venue, date);
        String subject = trim(couples + (date == null ? "" : " · " + DAY.format(date))
                + (place == null ? (venue == null ? "" : " · " + venue) : " · " + place), 240);

        var existing = conversations.findById(conversationId);
        if (existing.isPresent()) {
            // An earlier demo build named the group after the venue's contact card. Rename in place
            // rather than opening a second group for the same wedding.
            Conversation group = existing.get();
            if (!subject.equals(group.getSubject())) {
                group.setSubject(subject);
                group.setDescription(subject);
                conversations.save(group);
            }
            return;
        }

        LocalDateTime opened = LocalDateTime.now().minusDays(6);
        var conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setCustomer(owner);
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversation.setChannel(Channel.WHATSAPP);
        // WhatsApp names a group, not a person: the couple, then which wedding it is.
        conversation.setSubject(subject);
        conversation.setDescription(subject);
        conversation.setLastMessageAt(opened.plusMinutes(script.size() * 37L));
        conversation.setLastMessagePreview(script.getLast().body());
        conversation.setUnreadCount(2);
        conversations.save(conversation);

        for (int i = 0; i < script.size(); i++) {
            Line line = script.get(i);
            var message = new ConversationMessage();
            message.setId(stableId("message", conversationId + ":" + i));
            message.setConversation(Ref.of(Conversation.class, conversationId));
            message.setChannel(Channel.WHATSAPP);
            message.setAuthorName(line.author());
            message.setBody(line.body());
            message.setDescription(trim(line.body(), 240));
            message.setSentAt(opened.plusMinutes(i * 37L));
            message.setKind(line.kind());
            message.setDirection(line.kind() == MessageKind.AGENT_REPLY
                    ? MessageDirection.OUTBOUND : MessageDirection.INBOUND);
            message.setDeliveryStatus(line.kind() == MessageKind.AGENT_REPLY
                    ? DeliveryStatus.DELIVERED : DeliveryStatus.NOT_APPLICABLE);
            messages.save(message);
        }
    }

    private record Line(String author, String body, MessageKind kind) {}

    /**
     * What the group says. Written off the event's own facts — the date, the venue, the guest count,
     * the suppliers actually staffed — so two weddings do not read as the same conversation, and so
     * the thread stays true when the demo data changes underneath it.
     */
    private List<Line> script(List<String> couple, List<String> crew, List<String> venues,
            EventProject event, String venue, LocalDate date) {
        String first = couple.getFirst();
        String second = couple.size() > 1 ? couple.get(1) : first;
        String florist = crew.isEmpty() ? null : crew.getFirst();
        String studio = crew.size() > 1 ? crew.get(1) : null;
        String house = venues.isEmpty() ? null : venues.getFirst();
        String when = date == null ? "the date" : DAY.format(date);
        String guests = event.getGuests() == null ? "the final count" : event.getGuests() + " guests";

        var lines = new ArrayList<Line>();
        lines.add(new Line("Wedding Planner", "Group created · " + String.join(", ", members(couple, crew, venues)),
                MessageKind.SYSTEM_EVENT));
        lines.add(new Line("Sofia · Wedding Planner", "Welcome everyone. This is the group for " + first + " and "
                + second + "'s wedding on " + when + (venue == null ? "" : " at " + venue)
                + ". Timings, deliveries and any change of plan go here so nobody hears it second hand.",
                MessageKind.AGENT_REPLY));
        lines.add(new Line(first, "Thank you Sofia! We are so excited. Quick one — we are now at "
                + guests + ", is that still fine for the seating plan?", MessageKind.CUSTOMER_MESSAGE));
        if (house != null) {
            lines.add(new Line(house, "That works for the terrace. Above 140 we move the dinner inside, "
                    + "so let us confirm the number two weeks before.", MessageKind.CUSTOMER_MESSAGE));
        }
        if (florist != null) {
            lines.add(new Line(florist, "Noted. I will bring the table samples to the site visit — "
                    + "peonies are in season for " + when + ".", MessageKind.CUSTOMER_MESSAGE));
        }
        lines.add(new Line(second, "Can we see the samples in the afternoon light? The ceremony is at 5.",
                MessageKind.CUSTOMER_MESSAGE));
        if (studio != null) {
            lines.add(new Line(studio, "Good for us — golden hour that week starts around 19:20, "
                    + "so we will plan the couple portraits right after the ceremony.",
                    MessageKind.CUSTOMER_MESSAGE));
        }
        lines.add(new Line("Sofia · Wedding Planner", "Perfect. Site visit set, samples in the afternoon, portraits "
                + "after the ceremony. I will send the run sheet here on Friday.", MessageKind.AGENT_REPLY));
        return lines;
    }

    /** The line WhatsApp prints when a group opens: who is in it. */
    private List<String> members(List<String> couple, List<String> crew, List<String> venues) {
        var all = new ArrayList<>(couple);
        all.add("Sofia · Wedding Planner");
        all.addAll(crew);
        all.addAll(venues);
        return all;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static UUID stableId(String kind, String key) {
        return UUID.nameUUIDFromBytes(("planner:groups:v1:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
