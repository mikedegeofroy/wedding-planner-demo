package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.*;
import com.weddingplanner.crm.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.repository.*;
import su.onno.crm.service.CrmCustomerBinding;
import su.onno.types.Ref;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:crm-test;DB_CLOSE_DELAY=-1", "planner.marketing.demo-history=false", "planner.crm.demo-threads=false", "planner.events.demo=false"})
@Transactional
class InquiryInboxIntegrationTest {
    @Autowired InquiryInboxService service;
    // Three inboxes now share the type; this suite is about the role folders.
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("rolesInbox")
    su.onno.crm.service.CrmInboxWorkspace workspace;
    @Autowired su.onno.crm.service.CrmWorkspaceService configuration;
    @Autowired LeadInquiryRepository leads;
    @Autowired ContactRepository contacts;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired InboxRepository inboxes;
    @Autowired CrmCustomerBinding<Contact> customerBinding;

    // Six inquiries, each addressed to a couple and imported as two people, plus the five
    // planner cards the agency roster seeds.
    private static final int CARDS = 17;

    // Six inquiries, six first messages — plus the form submission each of the two website
    // inquiries now records as a card of its own, ahead of the words that arrived with it.
    private static final int MESSAGES = 8;

    @Test
    void foldersUseExistingContactsAndLeaveLeadAttributionIntact() {
        var before = leads.findAllActive().getFirst();
        var contact = contacts.findActiveById(before.getContact().id()).orElseThrow();
        var initial = workspace.configure().apply(configuration.get().config()).folders();
        assertThat(initial).hasSize(6);
        assertThat(initial.getFirst().conversationIds()).hasSize(6);
        assertThat(initial.get(1).matchNone()).isTrue();
        contact.setInboxFolder(InboxFolder.PARTNERS);
        contacts.save(contact);
        var updated = workspace.configure().apply(configuration.get().config()).folders();
        assertThat(updated.getFirst().conversationIds()).hasSize(5);
        assertThat(updated.get(2).conversationIds()).hasSize(1);
        var after = leads.findActiveById(before.getId()).orElseThrow();
        assertThat(after.getContact()).isEqualTo(before.getContact());
        assertThat(after.getSource()).isEqualTo(before.getSource());
        assertThat(after.getChannel()).isEqualTo(before.getChannel());
    }

    @Test
    void exposesAiSummaryForTheCustomCard() {
        var whatsappLead = leads.findAllActive().stream()
                .filter(lead -> lead.getChannel() == LeadChannel.WHATSAPP)
                .findFirst().orElseThrow();

        assertThat(customerBinding.catalog().fields(whatsappLead.getContact().id()))
                .containsEntry("aiSummary", whatsappLead.getAiSummary());
        assertThat(customerBinding.catalog().displayFields().stream()
                .filter(field -> field.key().equals("customer.aiSummary"))
                .map(field -> field.label()))
                .containsExactly("AI summary");
    }

    @Test
    void importsSeededInquiriesOnceAndPreservesEditedContactsAndHistory() {
        assertThat(leads.findAllActive()).hasSize(6);
        assertThat(contacts.findAllActive()).hasSize(CARDS);
        assertThat(conversations.findAllActive()).hasSize(6);
        assertThat(messages.findAllActive()).hasSize(MESSAGES);
        var lead = leads.findAllActive().getFirst();
        var contact = contacts.findActiveById(lead.getContact().id()).orElseThrow();
        contact.setDescription("Edited contact name");
        contacts.save(contact);
        var before = messages.findAllActive().stream().map(m -> m.getBody()).toList();
        leads.findAllActive().forEach(l -> service.importInquiry(l.getId()));
        assertThat(contacts.findAllActive()).hasSize(CARDS);
        assertThat(conversations.findAllActive()).hasSize(6);
        assertThat(messages.findAllActive().stream().map(m -> m.getBody()).toList())
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(contacts.findActiveById(contact.getId()).orElseThrow().getDescription())
                .isEqualTo("Edited contact name");
        var whatsappInbox = inboxes.findActiveById(
                InquiryInboxService.stableId("inbox", su.onno.crm.domain.Channel.WHATSAPP)).orElseThrow();
        assertThat(whatsappInbox.getDescription()).isEqualTo("Planner WhatsApp");
    }

    @Test
    void reusesChosenContactAndDoesNotInventAMessageForBlankInquiries() {
        var contact = contacts.findAllActive().getFirst();
        var lead = new LeadInquiry();
        lead.setCoupleName("Another wedding");
        lead.setContact(Ref.of(Contact.class, contact.getId()));
        leads.save(lead);
        service.importInquiry(lead.getId());
        service.importInquiry(lead.getId());
        assertThat(contacts.findAllActive()).hasSize(CARDS);
        assertThat(conversations.findAllActive()).hasSize(7);
        assertThat(messages.findAllActive()).hasSize(MESSAGES);
        var conversation = conversations.findActiveById(
                InquiryInboxService.stableId("conversation", lead.getId().toString())).orElseThrow();
        assertThat(conversation.getCustomer()).isEqualTo(contact.getId());
        assertThat(conversation.getUnreadCount()).isZero();
    }

    @Test
    void doesNotResurrectDeletedConversations() {
        var lead = leads.findAllActive().getFirst();
        var id = InquiryInboxService.stableId("conversation", lead.getId().toString());
        var conversation = conversations.findActiveById(id).orElseThrow();
        conversation.setDeletionMark(true);
        conversations.save(conversation);
        service.importInquiry(lead.getId());
        assertThat(conversations.findActiveById(id)).isEmpty();
        assertThat(messages.findAllActive()).hasSize(MESSAGES);
    }

    @Test
    void rejectsDeletedContactWithoutCreatingHistory() {
        var contact = contacts.findAllActive().getFirst();
        contact.setDeletionMark(true);
        contacts.save(contact);
        var lead = new LeadInquiry();
        lead.setCoupleName("Rejected inquiry");
        lead.setContact(Ref.of(Contact.class, contact.getId()));
        leads.save(lead);
        assertThatThrownBy(() -> service.importInquiry(lead.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Choose an active contact");
        assertThat(conversations.findAllActive()).hasSize(6);
    }
}
