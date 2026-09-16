package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.*;
import com.weddingplanner.crm.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.types.Ref;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Imports an inquiry once. Existing messages and contact edits remain authoritative. */
@Service
public class InquiryInboxService {
    private final LeadInquiryRepository leads;
    private final ContactRepository contacts;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public InquiryInboxService(LeadInquiryRepository leads, ContactRepository contacts,
            InboxRepository inboxes, ConversationRepository conversations,
            ConversationMessageRepository messages) {
        this.leads = leads; this.contacts = contacts; this.inboxes = inboxes;
        this.conversations = conversations; this.messages = messages;
    }

    @Transactional
    public void importInquiry(UUID id) {
        var lead = leads.findActiveById(id).orElseThrow(() -> new IllegalArgumentException("Inquiry not found"));
        String channel = replyChannel(lead);
        UUID inboxId = stableId("inbox", channel);
        String inboxLabel = inboxLabel(channel);
        // Keep the visible sender concise, including for inboxes created by older demo versions.
        Inbox inbox = inboxes.findActiveById(inboxId).map(value -> {
            if (!inboxLabel.equals(value.getDescription())) {
                value.setDescription(inboxLabel);
                return inboxes.save(value);
            }
            return value;
        }).orElseGet(() -> {
            var value = new Inbox(); value.setId(inboxId);
            value.setDescription(inboxLabel);
            value.setChannel(channel); value.setAddress("planner-inquiry-archive:" + channel);
            return inboxes.save(value);
        });
        UUID conversationId = stableId("conversation", id.toString());
        // Include tombstones here only as an import marker: never recreate deleted history.
        if (conversations.findById(conversationId).isPresent()) return;
        Contact contact;
        if (lead.getContact() == null) {
            contact = importCouple(lead);
            lead.setContact(Ref.of(Contact.class, contact.getId()));
            leads.save(lead);
        } else {
            contact = contacts.findActiveById(lead.getContact().id())
                    .orElseThrow(() -> new IllegalArgumentException("Choose an active contact"));
        }
        var conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setCustomer(contact.getId());
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversation.setChannel(channel);
        conversation.setSubject(lead.getCoupleName() + " · wedding inquiry");
        conversation.setDescription(conversation.getSubject());
        var receivedAt = lead.getDate() != null ? lead.getDate() : java.time.LocalDateTime.now();
        conversation.setLastMessageAt(receivedAt);
        String body = lead.getOriginalMessage();
        boolean hasMessage = body != null && !body.isBlank();
        conversation.setLastMessagePreview(hasMessage ? body.substring(0, Math.min(500, body.length())) : "");
        conversation.setUnreadCount(hasMessage ? 1 : 0);
        conversations.save(conversation);
        String form = inquiryCard(lead);
        if (lead.getChannel() == LeadChannel.WEBSITE_FORM && !form.isEmpty()) {
            var card = new ConversationMessage();
            card.setId(stableId("inquiry-card", id.toString()));
            card.setConversation(Ref.of(Conversation.class, conversationId));
            card.setChannel(channel); card.setAuthorName(contact.getDescription());
            card.setBody(form); card.setDescription("Website inquiry");
            // A second before the message, so the form is read before the words that came with it.
            card.setSentAt(receivedAt.minusSeconds(1));
            card.setKind(MessageKind.SYSTEM_EVENT);
            card.setDirection(MessageDirection.INTERNAL);
            card.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
            messages.save(card);
        }
        if (hasMessage) {
            var message = new ConversationMessage();
            message.setId(stableId("message", id.toString()));
            message.setConversation(Ref.of(Conversation.class, conversationId));
            message.setChannel(channel); message.setAuthorName(contact.getDescription());
            message.setBody(body); message.setDescription("Original inquiry");
            message.setSentAt(receivedAt); message.setKind(MessageKind.CUSTOMER_MESSAGE);
            message.setDirection(MessageDirection.INBOUND);
            message.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
            messages.save(message);
        }
    }

    /**
     * Creates one contact per person named in the inquiry and links them as partners. The inquiry
     * is addressed to a couple; the contacts it produces are individuals, because everything a card
     * carries downstream — a phone, an inbox identity, an event party row, an invoice counterparty —
     * belongs to one of the two people, not to the pair. The first person named holds the inquiry's
     * own email and phone (that is whose message arrived) and is the one the conversation is filed
     * under; the rest start blank and are filled in as the planning team meets them.
     */
    private Contact importCouple(LeadInquiry lead) {
        var people = CoupleNames.split(lead.getCoupleName());
        if (people.isEmpty()) people = java.util.List.of("New inquiry");
        UUID primaryId = stableId("contact", lead.getId().toString());
        var created = new java.util.ArrayList<Contact>();
        for (int i = 0; i < people.size(); i++) {
            var person = new Contact();
            // Person 0 keeps the pre-split id so an inquiry imported by an older demo build keeps
            // pointing at the same card instead of gaining a duplicate.
            person.setId(i == 0 ? primaryId : partnerId(primaryId, i));
            person.setDescription(people.get(i));
            if (i == 0) {
                person.setEmail(lead.getEmail());
                person.setPhone(lead.getPhone());
            }
            created.add(person);
        }
        // A pair points at each other; a larger family points back at the person who wrote in.
        for (int i = 0; i < created.size(); i++) {
            Contact other = created.size() == 2 ? created.get(1 - i) : (i == 0 ? null : created.getFirst());
            if (other != null) created.get(i).setPartner(Ref.of(Contact.class, other.getId()));
        }
        for (Contact person : created) contacts.save(person);
        return created.getFirst();
    }

    /**
     * The id of the {@code index}-th further person on a card that named several. Derived from the
     * person who wrote in rather than from the inquiry, so the couple-splitting backfill — which
     * only ever sees the contact — lands on exactly the same ids as a fresh import.
     */
    public static UUID partnerId(UUID primaryContactId, int index) {
        return stableId("contact", primaryContactId + ":partner:" + index);
    }

    /** Public so a second seeder writes into the same id space instead of duplicating inboxes. */
    public static UUID stableId(String kind, String key) {
        return UUID.nameUUIDFromBytes(("planner:crm:v3:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static String channel(LeadChannel channel) {
        return switch (channel) {
            case INSTAGRAM -> Channel.INSTAGRAM;
            case WHATSAPP -> Channel.WHATSAPP;
            case EMAIL -> Channel.EMAIL;
            case WEBSITE_FORM -> Channel.WEB_CHAT;
        };
    }

    /**
     * The channel a reply to this inquiry goes out on.
     *
     * <p>A website form is a <em>source</em>, not a mailbox: nobody can answer a form submission,
     * and a thread that looks answerable but is not wastes the one minute a planner has to reply.
     * What can be answered is the return address the form asked for — so a form lead opens on the
     * couple's email, or on WhatsApp when a number is all they left. An inquiry that left neither
     * keeps the website channel and stays read-only, because that is the truth about it.</p>
     */
    public static String replyChannel(LeadInquiry lead) {
        if (lead.getChannel() != LeadChannel.WEBSITE_FORM) return channel(lead.getChannel());
        if (filled(lead.getEmail())) return Channel.EMAIL;
        if (filled(lead.getPhone())) return Channel.WHATSAPP;
        return Channel.WEB_CHAT;
    }

    /**
     * The form submission itself, as the chat renders it: what the couple filled in, and where the
     * click came from. It is an internal event rather than a message because the couple did not
     * send it to anyone — they filled in a page — and separating it from the words they typed is
     * what lets the words stay an ordinary first message on an answerable channel.
     */
    public static String inquiryCard(LeadInquiry lead) {
        // What the couple typed into the form. Attribution is not part of this test: every
        // submission has a source, even if it is "Direct / unknown", so a card built on that alone
        // would appear above inquiries where nobody filled anything in and say nothing about them.
        var captured = new StringBuilder();
        field(captured, "Wedding date", lead.getWeddingDate() == null ? null : lead.getWeddingDate().format(
                java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)));
        field(captured, "Guests", lead.getGuestCount() == null ? null : String.valueOf(lead.getGuestCount()));
        field(captured, "Location", lead.getPreferredLocation());
        field(captured, "Budget", LeadFacts.euros(lead.getBudget()));
        field(captured, "Reply to", lead.getEmail());
        field(captured, "Phone", lead.getPhone());
        if (captured.isEmpty()) return "";

        var card = new StringBuilder("Website inquiry");
        if (lead.getDate() != null)
            card.append(" · ").append(lead.getDate().format(
                    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", java.util.Locale.ENGLISH)));
        card.append(captured);
        field(card, "Source", source(lead));
        field(card, "Page", lead.getLandingPage());
        return card.toString();
    }

    private static String source(LeadInquiry lead) {
        String source = LeadFacts.label(lead.getSource());
        String campaign = filled(lead.getCampaign()) ? lead.getCampaign() : lead.getUtmSource();
        if (!filled(source)) return campaign;
        return filled(campaign) ? source + " · " + campaign : source;
    }

    private static void field(StringBuilder card, String label, String value) {
        if (filled(value)) card.append("\n").append(label).append(": ").append(value);
    }

    private static boolean filled(String value) { return value != null && !value.isBlank(); }

    public static String inboxLabel(String channel) {
        return switch (channel) {
            case Channel.WHATSAPP -> "Planner WhatsApp";
            case Channel.INSTAGRAM -> "Planner Instagram";
            case Channel.EMAIL -> "Planner Email";
            case Channel.WEB_CHAT -> "Planner Website";
            default -> "Planner " + channel;
        };
    }
}
