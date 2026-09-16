package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.service.InquiryInboxService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.domain.Inbox;
import su.onno.crm.domain.MessageDirection;
import su.onno.crm.domain.MessageKind;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.types.Ref;

/**
 * Conversations that look like a planner's actual week.
 *
 * <p>An imported inquiry produces one conversation carrying one inbound message, which is honest —
 * it is the moment the couple wrote in — but it leaves an inbox where every chat is a single
 * paragraph and nobody has ever replied. Nothing about reading a thread, resolving a contact across
 * channels, or the unread count can be shown on that.
 *
 * <p>So the couples nearest the top of the inbox get a real exchange: a booked couple is negotiating
 * over WhatsApp <em>and</em> being sent paperwork by email, an Instagram inquiry gets chased and
 * answers three days later. The same contact appears on two or three channels on purpose — that is
 * what the unified contact panel is for, and with one channel per couple it has nothing to unify.
 *
 * <p>Runs after the flagship event is settled, so the couple whose wedding the demo is built around
 * is always one of them. Without that it was luck: the threads went to the newest booked couples and
 * the one event anybody actually opens could be the one with an empty inbox.
 *
 * <p>Deterministic per contact, idempotent by conversation id, and additive: it never edits or
 * deletes a conversation the rest of the demo created. Set
 * {@code planner.crm.demo-threads=false} for a dataset of exactly the imported inquiries — which is
 * what the inbox and merge suites assert against.
 */
@Order(32)
@Component
@ConditionalOnProperty(name = "planner.crm.demo-threads", matchIfMissing = true)
public class ChannelThreadSeeder implements CommandLineRunner {

    /** Enough to fill the first screen of the inbox without rewriting the whole dataset. */
    private static final int COUPLES = 8;

    private final LeadInquiryRepository leads;
    private final ContactRepository contacts;
    private final org.springframework.beans.factory.ObjectProvider<FlagshipEventSeeder> flagship;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public ChannelThreadSeeder(LeadInquiryRepository leads, ContactRepository contacts, InboxRepository inboxes,
            ConversationRepository conversations, ConversationMessageRepository messages,
            org.springframework.beans.factory.ObjectProvider<FlagshipEventSeeder> flagship) {
        this.flagship = flagship;
        this.leads = leads;
        this.contacts = contacts;
        this.inboxes = inboxes;
        this.conversations = conversations;
        this.messages = messages;
    }

    /** One message in a scripted exchange. {@code fromCouple} is who is speaking. */
    private record Turn(boolean fromCouple, String body) {}

    @Override
    public void run(String... args) {
        // Newest first, and only couples far enough along to have talked to anyone: a lead that is
        // still unqualified has not had three days of back-and-forth about a site visit.
        var talkative = leads.findAllActive().stream()
                .filter(lead -> lead.getContact() != null && lead.getDate() != null)
                .filter(lead -> lead.isMet() || lead.isBooked())
                .sorted(Comparator.comparing(LeadInquiry::getDate).reversed())
                .limit(COUPLES)
                .toList();

        // The demo's own wedding goes first, then the couples who wrote in most recently. Ordered
        // that way on purpose: the flagship's threads are seeded into the newest slots, so the event
        // someone is being shown is also the conversation at the top of the inbox.
        var candidates = new ArrayList<LeadInquiry>();
        flagshipLead().ifPresent(candidates::add);
        for (LeadInquiry lead : talkative) {
            if (candidates.stream().noneMatch(chosen -> chosen.getId().equals(lead.getId()))) {
                candidates.add(lead);
            }
        }

        // Inquiries are generated right up to the present minute, so a thread that ended "a couple of
        // hours ago" is not the newest thing in the inbox — it lands below a stranger's one-line
        // enquiry. These are the conversations worth opening the demo on, so they end minutes ago.
        LocalDateTime now = LocalDateTime.now();
        int slot = 0;
        for (LeadInquiry lead : candidates) {
            Contact contact = contacts.findActiveById(lead.getContact().id()).orElse(null);
            if (contact == null) continue;
            Random random = new Random(contact.getId().getMostSignificantBits());
            String origin = InquiryInboxService.replyChannel(lead);
            for (String channel : channelsFor(origin, lead.isBooked(), random)) {
                LocalDateTime endedAt = now.minusMinutes(3L + slot * 34L).minusSeconds(random.nextInt(600));
                seedThread(lead, contact, channel, script(channel, lead, contact, random), endedAt);
                slot++;
            }
        }
    }

    /** The inquiry behind the flagship event's client, when the events side of the demo is on. */
    private java.util.Optional<LeadInquiry> flagshipLead() {
        var seeder = flagship.getIfAvailable();
        if (seeder == null) return java.util.Optional.empty();
        return seeder.flagship()
                .map(com.weddingplanner.crm.events.domain.EventProject::getClient)
                .filter(java.util.Objects::nonNull)
                .flatMap(client -> leads.findAllActive().stream()
                        .filter(lead -> lead.getContact() != null && lead.getContact().id().equals(client.id()))
                        .filter(lead -> lead.getDate() != null)
                        .findFirst());
    }

    /**
     * Which channels this couple is talking on. Always the one they wrote in through, plus the ones
     * a planner picks up as a relationship gets real: WhatsApp once there is a date to argue about,
     * email once there is a contract to send.
     */
    private static List<String> channelsFor(String origin, boolean booked, Random random) {
        var picked = new ArrayList<String>();
        picked.add(origin);
        for (String candidate : booked
                ? List.of(Channel.WHATSAPP, Channel.EMAIL)
                : List.of(random.nextBoolean() ? Channel.WHATSAPP : Channel.EMAIL)) {
            if (!picked.contains(candidate)) picked.add(candidate);
        }
        return picked;
    }

    private void seedThread(LeadInquiry lead, Contact contact, String channel, List<Turn> script,
            LocalDateTime endedAt) {
        if (script.isEmpty()) return;
        UUID conversationId = InquiryInboxService.stableId(
                "conversation", "thread:" + lead.getId() + ":" + channel);
        // Never recreate history someone deleted, and never write the same thread twice.
        if (conversations.findById(conversationId).isPresent()) return;

        Inbox inbox = inbox(channel);
        // Space the turns out backwards from the end so the last message is the newest.
        LocalDateTime[] sentAt = new LocalDateTime[script.size()];
        LocalDateTime cursor = endedAt;
        for (int i = script.size() - 1; i >= 0; i--) {
            sentAt[i] = cursor;
            cursor = cursor.minusMinutes(25L + (long) (i + 1) * 47L);
        }

        var conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setCustomer(contact.getId());
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversation.setChannel(channel);
        conversation.setSubject(lead.getCoupleName() + " · " + subject(channel, lead.isBooked()));
        conversation.setDescription(conversation.getSubject());
        Turn last = script.getLast();
        conversation.setLastMessageAt(sentAt[script.size() - 1]);
        conversation.setLastMessagePreview(preview(last.body()));
        // Only an unanswered couple is unread: a thread the planner closed out is not waiting.
        conversation.setUnreadCount(last.fromCouple() ? 1 : 0);
        conversations.save(conversation);

        for (int i = 0; i < script.size(); i++) {
            Turn turn = script.get(i);
            var message = new ConversationMessage();
            message.setId(InquiryInboxService.stableId("message", conversationId + ":" + i));
            message.setConversation(Ref.of(Conversation.class, conversationId));
            message.setChannel(channel);
            message.setAuthorName(turn.fromCouple() ? contact.getDescription() : "Sofia");
            message.setBody(turn.body());
            message.setDescription(preview(turn.body()));
            message.setSentAt(sentAt[i]);
            message.setKind(turn.fromCouple() ? MessageKind.CUSTOMER_MESSAGE : MessageKind.AGENT_REPLY);
            message.setDirection(turn.fromCouple() ? MessageDirection.INBOUND : MessageDirection.OUTBOUND);
            message.setDeliveryStatus(turn.fromCouple() ? DeliveryStatus.NOT_APPLICABLE : DeliveryStatus.DELIVERED);
            messages.save(message);
        }
    }

    private Inbox inbox(String channel) {
        UUID inboxId = InquiryInboxService.stableId("inbox", channel);
        return inboxes.findActiveById(inboxId).orElseGet(() -> {
            var value = new Inbox();
            value.setId(inboxId);
            value.setDescription(InquiryInboxService.inboxLabel(channel));
            value.setChannel(channel);
            value.setAddress("planner-inquiry-archive:" + channel);
            return inboxes.save(value);
        });
    }

    private static String subject(String channel, boolean booked) {
        if (Channel.EMAIL.equals(channel)) return booked ? "contract and payment schedule" : "proposal";
        if (Channel.WHATSAPP.equals(channel)) return booked ? "planning" : "dates and site visit";
        return "wedding inquiry";
    }

    private static String preview(String body) {
        String flat = body.replace('\n', ' ').trim();
        return flat.length() <= 180 ? flat : flat.substring(0, 180);
    }

    /**
     * What the two of them actually said. Written per channel because the channel changes the
     * register: WhatsApp is short and logistical, email carries the paperwork, Instagram is where a
     * stranger opens with a question.
     */
    private static List<Turn> script(String channel, LeadInquiry lead, Contact contact, Random random) {
        String first = contact.getDescription().split(" ")[0];
        String where = lead.getPreferredLocation() == null ? "Lake Como" : lead.getPreferredLocation();
        String guests = lead.getGuestCount() == null ? "about 120" : String.valueOf(lead.getGuestCount());

        if (Channel.WHATSAPP.equals(channel)) {
            var turns = new ArrayList<>(List.of(
                    new Turn(false, "Hi " + first + " — Sofia from the planning team. Lovely to have you both. "
                            + "Are you fixed on " + where + ", or would you look at somewhere quieter for "
                            + guests + " guests?"),
                    new Turn(true, "We keep coming back to " + where + " honestly. My mother is set on it."),
                    new Turn(false, "Then we start there. Two villas take " + guests
                            + " without a marquee, which saves you a week of build. Can I hold a date while you decide?"),
                    new Turn(true, "Yes please. What is the latest we can confirm without losing it?"),
                    new Turn(false, "Ten days, and I would not push it further — the good weekends go first.")));
            if (lead.isBooked()) {
                turns.add(new Turn(true, "We spoke last night. Let's go ahead."));
                turns.add(new Turn(false, "Wonderful. Sending the contract and the payment schedule by email now."));
            } else if (random.nextBoolean()) {
                turns.add(new Turn(true, "Understood. Can we see it before we decide?"));
            }
            return turns;
        }

        if (Channel.EMAIL.equals(channel)) {
            if (lead.isBooked()) {
                return List.of(
                        new Turn(false, "Attached is the contract and the payment schedule we discussed: 30% to "
                                + "confirm, 40% ninety days out, the balance two weeks before.\n\n"
                                + "The deposit holds the villa and the band. Everything else stays flexible "
                                + "until the spring."),
                        new Turn(true, "Read it all through. One question — the 40% falls in the week we are "
                                + "away. Can it move a few days?"),
                        new Turn(false, "Moved to the Monday after you are back. Revised schedule attached, "
                                + "nothing else changed."),
                        new Turn(true, "Signed and sent back. Deposit goes out tomorrow morning."));
            }
            return List.of(
                    new Turn(false, "Lovely to speak earlier. The proposal is attached — two villas, both "
                            + "available for your dates, with costs broken down per head so you can see where "
                            + "the money actually goes.\n\nNo rush on this. Read it and tell me what you hate."),
                    new Turn(true, "Thank you. We are comparing it against one other planner this week and "
                            + "will come back to you either way."));
        }

        // Instagram: how a stranger arrives, and what it takes to get an answer back.
        var turns = new ArrayList<>(List.of(
                new Turn(false, "Hi " + first + "! Thanks for reaching out. Do you have dates in mind, "
                        + "or are you still deciding on the season?"),
                new Turn(true, "Still deciding. We were thinking late summer but everyone tells us it is too hot.")));
        turns.add(new Turn(false, "Early September is the answer to that — the light is better and the venues "
                + "are cheaper than August. Shall I send a few options for " + where + "?"));
        if (lead.isMet()) {
            turns.add(new Turn(true, "Please do. And could we talk properly this week?"));
            turns.add(new Turn(false, "Booked you in. I will send a calendar invite over now."));
        }
        return turns;
    }
}
