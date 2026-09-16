package com.weddingplanner.crm.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Channel;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.DeliveryStatus;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.service.CrmAttachments;
import su.onno.crm.service.CrmMessageTransport;

/**
 * A channel that accepts replies, so the demo can be used rather than only looked at.
 *
 * <p>Without a transport the CRM is correctly disconnected: the composer greys out and says the
 * messaging channel is not connected, which is the truth — there is no WhatsApp account behind this
 * app. It is also the end of every demo, because the one thing anyone tries first is answering a
 * couple, and the reply goes nowhere.
 *
 * <p>So this stands in for the real connectors. A reply is written to the conversation exactly as a
 * real one is — the composer, the thread, the unread count and the delivery tick all behave — and is
 * then marked delivered instead of being handed to a provider. Nothing leaves the machine: no
 * account is configured, no address is contacted, and no message a couple sent is ever answered
 * anywhere but here.
 *
 * <p>Set {@code planner.crm.demo-channels=false} to get the disconnected behaviour back, which is
 * what a deployment wiring in {@code onno-crm-channels-starter} wants — the router refuses two
 * providers claiming the same conversation.
 */
@Component
@ConditionalOnProperty(name = "planner.crm.demo-channels", matchIfMissing = true)
public class DemoMessageTransport implements CrmMessageTransport {

    private final ConversationMessageRepository messages;
    private final CrmAttachments attachments;

    public DemoMessageTransport(ConversationMessageRepository messages, CrmAttachments attachments) {
        this.messages = messages;
        this.attachments = attachments;
    }

    @Override
    public boolean supports(Conversation conversation) {
        return conversation != null && conversation.getChannel() != null;
    }

    @Override
    public Connection connection(Conversation conversation) {
        String channel = conversation.getChannel();
        // A form submission has no return path — there is no thread on the other side to answer
        // into. Inquiries that left an address open on that address instead; one that left nothing
        // stays here, and says so rather than offering a composer that sends into nowhere.
        if (Channel.WEB_CHAT.equals(channel))
            return new Connection(true, label(channel), maxTextLength(channel), ReplyCapability.READ_ONLY,
                    "A website form has no return address. This inquiry left no email or phone to answer on.");
        return new Connection(true, label(channel), maxTextLength(channel), ReplyCapability.AVAILABLE, "")
                // Files travel wherever this deployment can store them, so the composer's paperclip
                // is offered on the same terms a real channel would offer it.
                .withAttachments(attachments.limit(), attachments.unavailableReason());
    }

    @Override
    public void enqueue(Conversation conversation, ConversationMessage message) {
        // A real bridge queues here and delivers after commit, because the provider call can fail.
        // There is no call to fail, so the reply is delivered the moment it is written.
        message.setDeliveryStatus(DeliveryStatus.DELIVERED);
        messages.save(message);
    }

    /** The provider's own limit, so a long reply is refused here for the reason it would be there. */
    private static int maxTextLength(String channel) {
        if (Channel.INSTAGRAM.equals(channel)) return 1000;
        if (Channel.WHATSAPP.equals(channel)) return 4096;
        if (Channel.WEB_CHAT.equals(channel)) return 2000;
        return 8000;
    }

    private static String label(String channel) {
        if (Channel.INSTAGRAM.equals(channel)) return "Instagram";
        if (Channel.WHATSAPP.equals(channel)) return "WhatsApp";
        if (Channel.EMAIL.equals(channel)) return "Email";
        if (Channel.WEB_CHAT.equals(channel)) return "Website chat";
        if (Channel.PHONE.equals(channel)) return "Phone";
        return channel;
    }
}
