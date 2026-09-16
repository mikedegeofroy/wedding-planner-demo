package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.EventParty;
import com.weddingplanner.crm.events.domain.BudgetArticle;
import com.weddingplanner.crm.events.domain.EventBudget;
import com.weddingplanner.crm.events.domain.EventBudgetLine;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.repository.BudgetArticleRepository;
import com.weddingplanner.crm.events.repository.EventBudgetRepository;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import com.weddingplanner.crm.repository.ContactRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.types.Ref;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The crew side of the demo. The imported estimate examples arrive as bare events; this attaches a
 * couple to each and staffs it with the suppliers a wedding actually runs on, each with a live
 * conversation — otherwise the Conversations inbox has nothing to fold by role or by event.
 */
// After EventExampleSeeder(20): the crew attaches to events that already exist.
@org.springframework.core.annotation.Order(30)
@ConditionalOnProperty(name = "planner.events.demo", havingValue = "true")
@Component
public class EventCrewSeeder implements CommandLineRunner {
    private record Crew(String name, String email, String phone, InboxFolder folder,
                        String scope, String channel, String subject, String message,
                        String commission, List<String> articles) {}

    private static final List<Crew> CREW = List.of(
            new Crew("Fiori di Como · Elena Rossi", "elena@fioridicomo.it", "+39 031 555 0142",
                    InboxFolder.CONTRACTORS, "Ceremony and reception florals",
                    Channel.WHATSAPP, "Florals · Lake Como",
                    "Buongiorno! Peonies are in season for the June date. I can hold the quote for 14 days.",
                    "12", List.of("flower", "flowers", "decor", "decoration")),
            new Crew("Studio Lume · Marco Bianchi", "marco@studiolume.com", "+39 02 555 0188",
                    InboxFolder.CONTRACTORS, "Photography and film",
                    Channel.EMAIL, "Photo + film coverage",
                    "Attaching the two-shooter package and the drone permit for the villa. Let me know on the second day.",
                    "10", List.of("photo", "video", "filmmaker", "videographer")),
            new Crew("Villa Serbelloni Events", "events@villaserbelloni.example", "+39 031 555 0100",
                    InboxFolder.VENUES, "Venue, terrace and rooms",
                    Channel.EMAIL, "Venue hold · terrace",
                    "The terrace is on a provisional hold until month end. Confirming the guest count moves it to firm.",
                    "8", List.of("rent of", "location fee", "rent of the venue")));

    private final EventProjectRepository events;
    private final EventBudgetRepository budgets;
    private final BudgetArticleRepository articles;
    private final EventPartyRepository parties;
    private final ContactRepository contacts;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public EventCrewSeeder(EventProjectRepository events, EventBudgetRepository budgets,
            BudgetArticleRepository articles, EventPartyRepository parties,
            ContactRepository contacts, InboxRepository inboxes, ConversationRepository conversations,
            ConversationMessageRepository messages) {
        this.events = events; this.budgets = budgets; this.articles = articles;
        this.parties = parties; this.contacts = contacts;
        this.inboxes = inboxes; this.conversations = conversations; this.messages = messages;
    }

    @Override
    public void run(String... args) {
        var projects = events.findAllActive();
        if (projects.isEmpty()) return;
        // Every step below is keyed by a stable id and skips what already exists, so a restart
        // neither duplicates the crew nor overwrites edits made in the demo.
        // A contact card is one person, so a couple is two cards linked by Contact.partner. Take
        // the person as the entry point and pull their partner in beside them, and skip anyone
        // already reachable as someone else's partner so a pair is not staffed onto two events.
        var clients = contacts.findAllActive().stream()
                .filter(contact -> contact.getInboxFolder() == null || contact.getInboxFolder() == InboxFolder.CLIENTS)
                .toList();
        var byId = clients.stream().collect(Collectors.toMap(Contact::getId, client -> client));
        var seen = new HashSet<UUID>();
        var households = new ArrayList<List<Contact>>();
        for (Contact client : clients) {
            if (!seen.add(client.getId())) continue;
            var household = new ArrayList<Contact>(List.of(client));
            Contact partner = client.getPartner() == null ? null : byId.get(client.getPartner().id());
            if (partner != null && seen.add(partner.getId())) household.add(partner);
            households.add(List.copyOf(household));
        }
        if (households.isEmpty()) return;
        for (int i = 0; i < projects.size(); i++) {
            EventProject event = projects.get(i);
            // A wedding booked from an inquiry names its own couple, and that is who is staffed on
            // it. The imported estimate examples deliberately carry no client or dates — that is
            // what makes them examples — so they borrow a demo household in turn instead. Either
            // way both halves of the couple join as participants, which is what the Conversations
            // inbox folds by anyway.
            List<Contact> household = event.getClient() == null
                    ? households.get(i % households.size())
                    : EventBookSeeder.household(contacts, event.getClient());
            for (Contact person : household) party(event, person.getId(), InboxFolder.CLIENTS, "Wedding couple");
            for (Crew crew : CREW) {
                Contact supplier = supplier(crew);
                party(event, supplier.getId(), crew.folder(), crew.scope());
                conversation(crew, supplier, event);
            }
            assign(event);
        }
    }

    /**
     * One supplier card, shared across events — the same florist works several weddings. The card
     * carries the rate they rebate to Wedding Planner, which is what makes the event margin panel show
     * anything. An existing card only has the rate filled in when it is still blank, so a rate
     * edited in the demo survives a restart.
     */
    private Contact supplier(Crew crew) {
        UUID id = stableId("contact", crew.name());
        var existing = contacts.findActiveById(id);
        if (existing.isPresent()) {
            Contact contact = existing.get();
            if (contact.getCommissionRate() == null) {
                contact.setCommissionRate(new java.math.BigDecimal(crew.commission()));
                return contacts.save(contact);
            }
            return contact;
        }
        var contact = new Contact();
        contact.setId(id);
        contact.setDescription(crew.name());
        contact.setEmail(crew.email());
        contact.setPhone(crew.phone());
        contact.setInboxFolder(crew.folder());
        contact.setCommissionRate(new java.math.BigDecimal(crew.commission()));
        return contacts.save(contact);
    }

    /**
     * Put the demo suppliers behind the estimate articles that are obviously theirs — the florist
     * on the flowers, the studio on the photo and film, the venue on the rentals. Prices are never
     * touched: an article is only given an owner, which is what both committing it to a supplier
     * and charging commission on it need. A line that already names a contractor is left alone.
     */
    private void assign(EventProject event) {
        for (EventBudget budget : budgets.findAllActive()) {
            if (budget.getEvent() == null || !event.getId().equals(budget.getEvent().id())) continue;
            boolean changed = false;
            for (EventBudgetLine line : budget.getItems()) {
                if (line.getContractor() != null || line.getArticle() == null) continue;
                String article = articles.findActiveById(line.getArticle().id())
                        .map(BudgetArticle::getDescription).orElse("").toLowerCase(java.util.Locale.ROOT);
                if (article.isBlank()) continue;
                for (Crew crew : CREW) {
                    if (crew.articles().stream().noneMatch(article::contains)) continue;
                    line.setContractor(Ref.of(Contact.class, stableId("contact", crew.name())));
                    changed = true;
                    break;
                }
            }
            if (changed) budgets.save(budget);
        }
    }

    private void party(EventProject event, UUID contact, InboxFolder role, String scope) {
        UUID id = stableId("party", event.getId() + ":" + contact);
        if (parties.findActiveById(id).isPresent()) return;
        var party = new EventParty();
        party.setId(id);
        party.setEvent(Ref.of(EventProject.class, event.getId()));
        party.setContact(Ref.of(Contact.class, contact));
        party.setRole(role);
        party.setScope(scope);
        parties.save(party);
    }

    /** One inbound message per supplier, so they arrive in the inbox like any other counterparty. */
    private void conversation(Crew crew, Contact supplier, EventProject event) {
        UUID conversationId = stableId("conversation", crew.name() + ":" + event.getId());
        var existing = conversations.findActiveById(conversationId);
        if (existing.isPresent()) {
            retitle(existing.get(), crew, event);
            return;
        }
        UUID inboxId = stableId("inbox", crew.channel());
        Inbox inbox = inboxes.findActiveById(inboxId).orElseGet(() -> {
            var value = new Inbox();
            value.setId(inboxId);
            value.setDescription(label(crew.channel()));
            value.setChannel(crew.channel());
            value.setAddress("planner-crew:" + crew.channel());
            return inboxes.save(value);
        });
        var sentAt = LocalDateTime.now().minusDays(2);
        // A supplier works several weddings, and the contact pane merges their threads — name the
        // event in the message so the two do not read as the same message twice.
        String body = crew.message() + " (" + event.getDescription() + ")";
        var conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setCustomer(supplier.getId());
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversation.setChannel(crew.channel());
        conversation.setSubject(crew.subject() + " · " + event.getDescription());
        conversation.setDescription(conversation.getSubject());
        conversation.setLastMessageAt(sentAt);
        conversation.setLastMessagePreview(body);
        conversation.setUnreadCount(1);
        conversations.save(conversation);
        var message = new ConversationMessage();
        message.setId(stableId("message", conversationId.toString()));
        message.setConversation(Ref.of(Conversation.class, conversationId));
        message.setChannel(crew.channel());
        message.setAuthorName(crew.name());
        message.setBody(body);
        message.setDescription(crew.subject());
        message.setSentAt(sentAt);
        message.setKind(MessageKind.CUSTOMER_MESSAGE);
        message.setDirection(MessageDirection.INBOUND);
        message.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(message);
    }

    /**
     * Earlier demo databases carry the supplier's message without the event in it, which reads as
     * the same message twice once the contact pane merges their threads. Name the event in place.
     */
    private void retitle(Conversation conversation, Crew crew, EventProject event) {
        String body = crew.message() + " (" + event.getDescription() + ")";
        if (body.equals(conversation.getLastMessagePreview())) return;
        conversation.setLastMessagePreview(body);
        conversations.save(conversation);
        messages.findActiveById(stableId("message", conversation.getId().toString())).ifPresent(message -> {
            message.setBody(body);
            messages.save(message);
        });
    }

    private static String label(String channel) {
        return switch (channel) {
            case Channel.WHATSAPP -> "Planner WhatsApp";
            case Channel.EMAIL -> "Planner Email";
            default -> "Planner " + channel;
        };
    }

    /** Package-visible so the group chats land in the same WhatsApp inbox the crew writes from. */
    static UUID stableId(String kind, String key) {
        return UUID.nameUUIDFromBytes(("planner:crew:v1:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
