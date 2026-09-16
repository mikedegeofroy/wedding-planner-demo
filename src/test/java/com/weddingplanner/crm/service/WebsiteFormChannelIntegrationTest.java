package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.*;
import com.weddingplanner.crm.repository.*;
import com.weddingplanner.crm.seed.ContactChannelIdentities;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.CrmMessageTransport.ReplyCapability;
import su.onno.types.Ref;
import static org.assertj.core.api.Assertions.*;

/** A website form is a source of leads, so the thread it opens runs on an answerable channel. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:website-form;DB_CLOSE_DELAY=-1",
        "planner.marketing.demo-history=false", "planner.crm.demo-threads=false", "planner.events.demo=false"})
@Transactional
class WebsiteFormChannelIntegrationTest {

    @Autowired InquiryInboxService service;
    @Autowired DemoMessageTransport transport;
    @Autowired ContactChannelIdentities channelIdentities;
    @Autowired LeadInquiryRepository leads;
    @Autowired ContactRepository contacts;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired ContactIdentityRepository identities;

    private LeadInquiry formLead(String email, String phone) {
        var lead = new LeadInquiry();
        lead.setCoupleName("Ada & Rex Whitfield");
        lead.setChannel(LeadChannel.WEBSITE_FORM);
        lead.setSource(MarketingSource.GOOGLE_ADS);
        lead.setEmail(email);
        lead.setPhone(phone);
        lead.setDate(LocalDateTime.now().minusDays(2).withNano(0));
        lead.setOriginalMessage("Hello — we are planning our wedding in Portofino.");
        lead.setWeddingDate(LocalDate.of(2027, 10, 12));
        lead.setGuestCount(146);
        lead.setPreferredLocation("Portofino");
        lead.setBudget(new BigDecimal("300000"));
        lead.setCampaign("wedding-planner-tuscany");
        lead.setLandingPage("/destination-weddings-italy");
        return leads.save(lead);
    }

    private Conversation imported(LeadInquiry lead) {
        service.importInquiry(lead.getId());
        return conversations.findActiveById(
                InquiryInboxService.stableId("conversation", lead.getId().toString())).orElseThrow();
    }

    @Test void anInquiryWithAnEmailOpensOnEmailRatherThanOnTheWebsite() {
        var conversation = imported(formLead("ada.whitfield@example.com", null));
        assertThat(conversation.getChannel()).isEqualTo(Channel.EMAIL);
        assertThat(conversation.getInbox().id())
                .isEqualTo(InquiryInboxService.stableId("inbox", Channel.EMAIL));
        assertThat(transport.connection(conversation).canSend()).isTrue();
    }

    @Test void anInquiryThatLeftOnlyANumberOpensOnWhatsApp() {
        var conversation = imported(formLead(null, "+39 333 123 4567"));
        assertThat(conversation.getChannel()).isEqualTo(Channel.WHATSAPP);
        assertThat(transport.connection(conversation).canSend()).isTrue();
    }

    /** The one case that genuinely cannot be answered says so instead of offering a composer. */
    @Test void anInquiryThatLeftNoReturnAddressStaysOnTheWebsiteAndIsReadOnly() {
        var conversation = imported(formLead(null, null));
        assertThat(conversation.getChannel()).isEqualTo(Channel.WEB_CHAT);
        var connection = transport.connection(conversation);
        assertThat(connection.canSend()).isFalse();
        assertThat(connection.replyCapability()).isEqualTo(ReplyCapability.READ_ONLY);
        assertThat(connection.unavailableReason()).contains("no return address");
    }

    @Test void theFormItselfBecomesACardAheadOfTheWordsThatCameWithIt() {
        var lead = formLead("ada.whitfield@example.com", "+39 333 123 4567");
        var conversation = imported(lead);
        var thread = messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversation.getId()));
        assertThat(thread).hasSize(2);

        var card = thread.getFirst();
        assertThat(card.getKind()).isEqualTo(MessageKind.SYSTEM_EVENT);
        assertThat(card.getDirection()).isEqualTo(MessageDirection.INTERNAL);
        assertThat(card.getChannel()).isEqualTo(Channel.EMAIL);
        assertThat(card.getBody()).startsWith("Website inquiry · ")
                .contains("\nWedding date: 12 Oct 2027")
                .contains("\nGuests: 146")
                .contains("\nLocation: Portofino")
                .contains("\nReply to: ada.whitfield@example.com")
                .contains("\nPhone: +39 333 123 4567")
                .contains("\nSource: Google Ads · wedding-planner-tuscany")
                .contains("\nPage: /destination-weddings-italy");

        var message = thread.get(1);
        assertThat(message.getKind()).isEqualTo(MessageKind.CUSTOMER_MESSAGE);
        assertThat(message.getDirection()).isEqualTo(MessageDirection.INBOUND);
        assertThat(message.getBody()).isEqualTo(lead.getOriginalMessage());
        assertThat(card.getSentAt()).isBefore(message.getSentAt());
    }

    /** What the form captured is what the contact can be reached on — unverified, and said so. */
    @Test void capturedDetailsBecomeChannelIdentities() {
        var lead = formLead("ada.whitfield@example.com", "+39 333 123 4567");
        imported(lead);
        // importInquiry files the couple against its own copy of the inquiry, so read it back.
        var imported = leads.findActiveById(lead.getId()).orElseThrow();
        var contact = contacts.findActiveById(imported.getContact().id()).orElseThrow();
        assertThat(identities.findByCustomerAndDeletionMarkFalse(contact.getId())).isEmpty();

        channelIdentities.run();
        var linked = identities.findByCustomerAndDeletionMarkFalse(contact.getId());
        assertThat(linked).extracting(ContactIdentity::getChannel)
                .containsExactlyInAnyOrder(Channel.EMAIL, Channel.WHATSAPP, Channel.PHONE);
        assertThat(linked).allSatisfy(identity -> assertThat(identity.isVerified()).isFalse());
        assertThat(linked).filteredOn(identity -> Channel.EMAIL.equals(identity.getChannel()))
                .singleElement().extracting(ContactIdentity::getAddress).isEqualTo("ada.whitfield@example.com");

        // A second boot must not double the list.
        channelIdentities.run();
        assertThat(identities.findByCustomerAndDeletionMarkFalse(contact.getId())).hasSameSizeAs(linked);
    }
}
