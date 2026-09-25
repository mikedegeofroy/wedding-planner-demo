package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.BudgetBand;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.domain.LeadChannel;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.MarketingSource;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
 * A first message from someone the planner has never heard from, filed where it belongs.
 *
 * <p>It happens in three steps, each visible in the inbox as it happens. The message lands on a new
 * card in <em>Needs review</em> — so it is in All chats and nowhere else. {@link JevClassifier} then
 * reads it. Finally the card is refiled: a couple into Clients with an inquiry carrying the budget
 * they wrote, a supplier into Contractors, a venue into Venues, press into Partners. An answer the
 * model would not commit to leaves the card in Needs review for a person to decide, and the chat
 * says so in a card of its own.</p>
 *
 * <p>Each step commits on its own so the inbox's live refresh shows the chat arrive, and then move.</p>
 */
@Service
public class InboundTriageService {

    /** Below this the sender type is a guess, and a guess does not get to file a chat. */
    public static final double ROUTE_CONFIDENCE = 0.6;
    public static final String CARD_HEADING = "Jev classification";

    public record Inbound(String name, String channel, String message, String email, String phone) {}

    public record Outcome(UUID contactId, UUID conversationId, UUID inquiryId, InboxFolder folder,
                          BudgetBand band, JevClassifier.Result classification) {}

    private final JevClassifier classifier;
    private final ContactRepository contacts;
    private final LeadInquiryRepository leads;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final PipelineStageService stages;
    private final TransactionTemplate tx;

    public InboundTriageService(JevClassifier classifier, ContactRepository contacts, LeadInquiryRepository leads,
            InboxRepository inboxes, ConversationRepository conversations, ConversationMessageRepository messages,
            PipelineStageService stages, TransactionTemplate tx) {
        this.classifier = classifier; this.contacts = contacts; this.leads = leads; this.inboxes = inboxes;
        this.conversations = conversations; this.messages = messages; this.stages = stages; this.tx = tx;
    }

    /** Lands the message, waits {@code pauseMillis} so the audience sees it land, then classifies and files it. */
    public Outcome receive(Inbound inbound, long pauseMillis) {
        String channel = channel(inbound.channel());
        var arrived = tx.execute(status -> arrive(inbound, channel));
        if (pauseMillis > 0) {
            try { Thread.sleep(Math.min(pauseMillis, 10_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        var result = classifier.classify(channel, inbound.name(), inbound.message());
        return tx.execute(status -> file(inbound, channel, arrived, result));
    }

    private record Arrived(UUID contactId, UUID conversationId, LocalDateTime at) {}

    private Arrived arrive(Inbound inbound, String channel) {
        var at = LocalDateTime.now();
        var contact = new Contact();
        contact.setId(UUID.randomUUID());
        contact.setDescription(inbound.name());
        contact.setEmail(blank(inbound.email()) ? null : inbound.email().strip());
        contact.setPhone(blank(inbound.phone()) ? null : inbound.phone().strip());
        contact.setInboxFolder(InboxFolder.UNSORTED);
        contacts.save(contact);

        var conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setCustomer(contact.getId());
        conversation.setInbox(Ref.of(Inbox.class, inbox(channel).getId()));
        conversation.setChannel(channel);
        conversation.setSubject(inbound.name() + " · new message");
        conversation.setDescription(conversation.getSubject());
        conversation.setLastMessageAt(at);
        conversation.setLastMessagePreview(preview(inbound.message()));
        conversation.setUnreadCount(1);
        conversations.save(conversation);

        var message = new ConversationMessage();
        message.setId(UUID.randomUUID());
        message.setConversation(Ref.of(Conversation.class, conversation.getId()));
        message.setChannel(channel);
        message.setAuthorName(inbound.name());
        message.setBody(inbound.message());
        message.setDescription(preview(inbound.message()));
        message.setSentAt(at);
        message.setKind(MessageKind.CUSTOMER_MESSAGE);
        message.setDirection(MessageDirection.INBOUND);
        message.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(message);
        return new Arrived(contact.getId(), conversation.getId(), at);
    }

    private Outcome file(Inbound inbound, String channel, Arrived arrived, JevClassifier.Result result) {
        String sender = result.sender().choice();
        boolean confident = result.senderConfidence() >= ROUTE_CONFIDENCE;
        InboxFolder folder = !confident ? InboxFolder.UNSORTED : switch (sender == null ? "" : sender) {
            case "couple" -> InboxFolder.CLIENTS;
            case "vendor" -> InboxFolder.CONTRACTORS;
            case "venue" -> InboxFolder.VENUES;
            case "press_partner" -> InboxFolder.PARTNERS;
            default -> InboxFolder.UNSORTED;
        };

        // The budget a couple wrote wins. Failing that, a band Jev is sure of files them at its floor,
        // and the card says the figure is an estimate rather than something they said.
        BigDecimal budget = result.budget();
        boolean estimated = false;
        if (budget == null && result.band().probability() >= ROUTE_CONFIDENCE) {
            budget = floor(result.band().choice());
            estimated = budget != null;
        }
        BudgetBand band = LeadInquiry.classifyBudget(budget);

        UUID inquiryId = null;
        var contact = contacts.findActiveById(arrived.contactId()).orElseThrow();
        contact.setInboxFolder(folder);
        contacts.save(contact);
        if (folder == InboxFolder.CLIENTS) {
            var lead = new LeadInquiry();
            lead.setCoupleName(inbound.name());
            lead.setContact(Ref.of(Contact.class, contact.getId()));
            lead.setEmail(contact.getEmail());
            lead.setPhone(contact.getPhone());
            lead.setChannel(leadChannel(channel));
            lead.setSource(Channel.INSTAGRAM.equals(channel) ? MarketingSource.ORGANIC_INSTAGRAM : MarketingSource.DIRECT);
            lead.setBudget(budget);
            lead.setGuestCount(result.guests());
            lead.setDate(arrived.at());
            lead.setOriginalMessage(inbound.message());
            lead.setAiSummary(summary(result, band, estimated));
            stages.byRole(StageRole.NEW).ifPresent(lead::setStage);
            leads.save(lead);
            inquiryId = lead.getId();
        }

        var conversation = conversations.findActiveById(arrived.conversationId()).orElseThrow();
        conversation.setSubject(inbound.name() + " · " + subject(folder, sender, confident));
        conversation.setDescription(conversation.getSubject());
        conversations.save(conversation);

        var card = new ConversationMessage();
        card.setId(UUID.randomUUID());
        card.setConversation(Ref.of(Conversation.class, arrived.conversationId()));
        card.setChannel(channel);
        card.setAuthorName("Jev");
        card.setBody(card(result, folder, confident, band, budget, estimated));
        card.setDescription(CARD_HEADING);
        // A moment after the message it read, so the card sits beneath the words it is about.
        card.setSentAt(arrived.at().plusSeconds(1));
        card.setKind(MessageKind.SYSTEM_EVENT);
        card.setDirection(MessageDirection.INTERNAL);
        card.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(card);

        return new Outcome(contact.getId(), arrived.conversationId(), inquiryId, folder, band, result);
    }

    /**
     * The card as plain {@code Key: value} lines — readable as it is if the chat renderer is missing,
     * and parsed by {@code JevClassification.tsx} into the card the demo shows.
     */
    private static String card(JevClassifier.Result r, InboxFolder folder, boolean confident, BudgetBand band,
                               BigDecimal budget, boolean estimated) {
        var lines = new LinkedHashMap<String, String>();
        lines.put("Engine", r.live() ? "TypeSafe " + r.model() : "Keyword rules (offline)");
        if (r.live()) lines.put("Latency", r.millis() + " ms");
        lines.put("Sender", senderLabel(r.sender().choice()) + " · " + pct(r.sender().probability()));
        lines.put("Confidence", pct(r.senderConfidence()));
        lines.put("Segment", LeadFacts.label(band) + (r.band().choice() == null ? "" : " · " + pct(r.band().probability())));
        lines.put("Segment color", LeadFacts.color(band));
        if (budget != null) lines.put("Budget", LeadFacts.euros(budget) + (estimated ? " (estimated from band)" : ""));
        if (r.budgetSpan() != null) lines.put("Quoted", r.budgetSpan());
        if (r.guests() != null) lines.put("Guests", String.valueOf(r.guests()));
        lines.put("Urgency", pct(r.urgency()));
        lines.put("Routed to", folder.label() + (confident ? "" : " — confidence below " + pct(ROUTE_CONFIDENCE)));
        lines.put("Folder color", LeadFacts.color(folder));
        var body = new StringBuilder(CARD_HEADING);
        lines.forEach((k, v) -> { if (v != null) body.append('\n').append(k).append(": ").append(v); });
        return body.toString();
    }

    private static String summary(JevClassifier.Result r, BudgetBand band, boolean estimated) {
        return "First message classified by " + (r.live() ? "Jev" : "keyword rules") + ": "
                + senderLabel(r.sender().choice()).toLowerCase(Locale.ROOT) + " (" + pct(r.sender().probability()) + "), "
                + LeadFacts.label(band) + (estimated ? " (estimated)" : "")
                + (r.guests() == null ? "" : ", " + r.guests() + " guests")
                + ", urgency " + pct(r.urgency()) + ".";
    }

    private static String subject(InboxFolder folder, String sender, boolean confident) {
        if (!confident) return "needs review";
        return switch (folder) {
            case CLIENTS -> "wedding inquiry";
            case CONTRACTORS -> "supplier introduction";
            case VENUES -> "venue introduction";
            case PARTNERS -> "press & partnerships";
            default -> "spam".equals(sender) ? "likely spam" : "needs review";
        };
    }

    public static String senderLabel(String sender) {
        if (sender == null) return "Unknown";
        return switch (sender) {
            case "couple" -> "Couple";
            case "vendor" -> "Supplier";
            case "venue" -> "Venue";
            case "press_partner" -> "Press / partner";
            case "spam" -> "Spam";
            default -> sender;
        };
    }

    private static BigDecimal floor(String band) {
        if (band == null) return null;
        return switch (band) {
            case "FROM_300K_TO_499K" -> new BigDecimal("300000");
            case "FROM_500K_TO_749K" -> new BigDecimal("500000");
            case "FROM_750K" -> new BigDecimal("750000");
            default -> null;
        };
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

    private static String channel(String requested) {
        String value = requested == null ? "" : requested.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "WHATSAPP" -> Channel.WHATSAPP;
            case "EMAIL", "MAIL" -> Channel.EMAIL;
            default -> Channel.INSTAGRAM;
        };
    }

    private static LeadChannel leadChannel(String channel) {
        if (Channel.WHATSAPP.equals(channel)) return LeadChannel.WHATSAPP;
        if (Channel.EMAIL.equals(channel)) return LeadChannel.EMAIL;
        return LeadChannel.INSTAGRAM;
    }

    private static String pct(double p) { return Math.round(p * 100) + "%"; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String preview(String body) {
        String flat = body.replace('\n', ' ').strip();
        return flat.length() <= 180 ? flat : flat.substring(0, 180);
    }

    /** Ready-made first messages for the demo, keyed by the name the trigger script uses. */
    public static final Map<String, Inbound> SCENARIOS = scenarios();

    private static Map<String, Inbound> scenarios() {
        var s = new LinkedHashMap<String, Inbound>();
        s.put("couple-como", new Inbound("Sofia Marchetti", "instagram",
                "Ciao! My fiancé Luca and I are getting married next September and dream of a villa on Lake Como, "
                        + "around 160 guests over a three-day weekend with a welcome dinner. We were thinking somewhere "
                        + "around €600k all in, maybe a bit more for the right venue. Do you still have availability?",
                null, null));
        s.put("couple-vip", new Inbound("Charlotte Hayes", "email",
                "Dear team, my fiancé James and I are planning a wedding in the Amalfi coast for summer 2027. "
                        + "We expect 220 guests and budget is not really a constraint — we are thinking north of "
                        + "three quarters of a million. We'd like a call with your founder.",
                "charlotte.hayes@example.com", null));
        s.put("couple-small", new Inbound("Giulia Rossi", "whatsapp",
                "Hi! We're planning a small wedding in Tuscany, about 60 guests, next spring. "
                        + "Our budget is around €180,000. Is that something you'd take on?",
                null, "+39 347 555 0192"));
        s.put("vendor-florist", new Inbound("Marco Bellini", "email",
                "Good morning, I run Bellini Floral Studio in Milan. We'd love to be added to your preferred florist "
                        + "list for the 2027 season — attached is our portfolio and trade price sheet. Happy to offer "
                        + "a 10% planner commission.",
                "marco@bellinifloral.example", null));
        s.put("venue", new Inbound("Villa Aurelia Events", "email",
                "Hello from Villa Aurelia on Lake Garda! Our estate has reopened after restoration and can host "
                        + "up to 250 guests with 40 rooms on site. We'd love to invite your team for a site visit.",
                "events@villaaurelia.example", null));
        s.put("press", new Inbound("Elena Conti", "instagram",
                "Hi! I'm an editor at Vogue Sposa and we're preparing a feature on destination weddings in Italy. "
                        + "Would you be open to an interview and sharing a few real weddings?",
                null, null));
        s.put("spam", new Inbound("Growth Partners SEO", "email",
                "Boost your Google rankings in 30 days! We guarantee first-page placement with premium backlinks. "
                        + "Reply YES for a free audit.",
                "hello@growthpartners.example", null));
        s.put("ambiguous", new Inbound("Alex", "instagram", "hi, how much do you charge?", null, null));
        return java.util.Collections.unmodifiableMap(s);
    }
}
