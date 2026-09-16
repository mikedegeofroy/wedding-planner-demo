package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.LeadChannel;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.service.InquiryInboxService;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.*;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.repository.InboxRepository;
import su.onno.types.Ref;

/**
 * Moves the website-form threads onto a channel somebody can answer.
 *
 * <p>The first demo filed a form submission as a conversation on a "Planner Website" inbox, and the
 * transport then claimed that inbox was a connected channel. It is not one: a form is a page a
 * couple filled in, there is no thread on the other side, and a planner who typed a reply into it
 * was writing to nobody. The reply belongs on the return address the form asked for — the couple's
 * email, or WhatsApp when a number is all they left.</p>
 *
 * <p>So each of those conversations moves to that channel, its messages with it, and the form
 * submission itself becomes an internal event the chat draws as a card: what they filled in, and
 * which click brought them. Where the couple came from is not lost by the move — that lives on the
 * inquiry as {@code channel}/{@code source} and still reads "Website form" in the panel and in
 * every marketing chart.</p>
 *
 * <p>Idempotent: a second run finds nothing on the website channel and nothing missing a card.</p>
 */
// After ContactIdentityBackfill(16), which is where a couple gets the address this files them under.
@Order(17)
@Component
public class WebsiteFormChannels implements CommandLineRunner {

    private final LeadInquiryRepository leads;
    private final ContactRepository contacts;
    private final InboxRepository inboxes;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public WebsiteFormChannels(LeadInquiryRepository leads, ContactRepository contacts, InboxRepository inboxes,
            ConversationRepository conversations, ConversationMessageRepository messages) {
        this.leads = leads; this.contacts = contacts; this.inboxes = inboxes;
        this.conversations = conversations; this.messages = messages;
    }

    @Override
    public void run(String... args) {
        // Numbers first: a phone captured here is what decides where a couple who left no email
        // can be answered, and the sweep below reads it back off the contact.
        for (LeadInquiry lead : leads.findAllActive())
            if (lead.getChannel() == LeadChannel.WEBSITE_FORM) capturePhone(lead);

        // Every conversation on the website channel, not only the imported inquiry — earlier demo
        // builds also scripted whole back-and-forth threads onto it, which is the version of this
        // fiction that reads worst: a planner answering a form, and the form answering back.
        for (Conversation conversation : conversations.findAllActive())
            if (Channel.WEB_CHAT.equals(conversation.getChannel()))
                move(conversation, reachableOn(conversation.getCustomer()));

        for (LeadInquiry lead : leads.findAllActive()) {
            if (lead.getChannel() != LeadChannel.WEBSITE_FORM) continue;
            UUID conversationId = InquiryInboxService.stableId("conversation", lead.getId().toString());
            if (conversations.findActiveById(conversationId).isPresent()) card(lead, conversationId);
        }
        retireWebsiteInbox();
    }

    /**
     * The "Planner Website" account, switched off once nothing is filed under it. An inbox that
     * still exists is still an account, and the inbox's channel bar builds its facets from the
     * accounts it finds — leaving it active puts an empty website filter in front of a planner who
     * would reasonably expect it to hold the leads that came in through the site.
     */
    private void retireWebsiteInbox() {
        var website = inboxes.findActiveById(InquiryInboxService.stableId("inbox", Channel.WEB_CHAT)).orElse(null);
        if (website == null || !website.isActive()) return;
        boolean stillUsed = conversations.findAllActive().stream()
                .anyMatch(conversation -> Channel.WEB_CHAT.equals(conversation.getChannel()));
        if (stillUsed) return;
        website.setActive(false);
        inboxes.save(website);
    }

    /** Where this contact can actually be reached, or the website channel when nowhere can. */
    private String reachableOn(UUID customer) {
        Contact contact = customer == null ? null : contacts.findActiveById(customer).orElse(null);
        if (contact == null) return Channel.WEB_CHAT;
        if (contact.getEmail() != null && !contact.getEmail().isBlank()) return Channel.EMAIL;
        if (contact.getPhone() != null && !contact.getPhone().isBlank()) return Channel.WHATSAPP;
        return Channel.WEB_CHAT;
    }

    /**
     * A number for form leads seeded before the form asked for one. Two in three, drawn from the
     * inquiry's own id so a restart lands on the same numbers, and never over a value already
     * there — the phone on an existing card may have been typed by a person.
     */
    private void capturePhone(LeadInquiry lead) {
        if (lead.getPhone() != null && !lead.getPhone().isBlank()) return;
        var random = new Random(lead.getId().getLeastSignificantBits());
        if (random.nextInt(3) == 0) return;
        String phone = String.format("+39 3%02d %03d %04d",
                random.nextInt(100), random.nextInt(1000), random.nextInt(10000));
        lead.setPhone(phone);
        leads.save(lead);
        if (lead.getContact() == null) return;
        contacts.findActiveById(lead.getContact().id())
                .filter(contact -> contact.getPhone() == null || contact.getPhone().isBlank())
                .ifPresent(contact -> { contact.setPhone(phone); contacts.save(contact); });
    }

    /** The conversation and everything in it change channel together, or the thread reads as two. */
    private void move(Conversation conversation, String channel) {
        if (channel.equals(conversation.getChannel())) return;
        Inbox inbox = inbox(channel);
        conversation.setChannel(channel);
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversations.save(conversation);
        List<ConversationMessage> thread = messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversation.getId()));
        for (ConversationMessage message : thread) {
            if (channel.equals(message.getChannel())) continue;
            message.setChannel(channel);
            messages.save(message);
        }
    }

    /** The form submission, for a thread imported before it was recorded as one. */
    private void card(LeadInquiry lead, UUID conversationId) {
        UUID cardId = InquiryInboxService.stableId("inquiry-card", lead.getId().toString());
        // Tombstones count: a card someone deleted must not come back on the next boot.
        if (messages.findById(cardId).isPresent()) return;
        // A submission that captured nothing has nothing to show.
        String form = InquiryInboxService.inquiryCard(lead);
        if (form.isEmpty()) return;
        var receivedAt = lead.getDate() != null ? lead.getDate() : java.time.LocalDateTime.now();
        var contact = lead.getContact() == null ? null : contacts.findActiveById(lead.getContact().id()).orElse(null);
        var card = new ConversationMessage();
        card.setId(cardId);
        card.setConversation(Ref.of(Conversation.class, conversationId));
        card.setChannel(reachableOn(lead.getContact() == null ? null : lead.getContact().id()));
        card.setAuthorName(contact == null ? lead.getCoupleName() : contact.getDescription());
        card.setBody(form);
        card.setDescription("Website inquiry");
        card.setSentAt(receivedAt.minusSeconds(1));
        card.setKind(MessageKind.SYSTEM_EVENT);
        card.setDirection(MessageDirection.INTERNAL);
        card.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(card);
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
}
