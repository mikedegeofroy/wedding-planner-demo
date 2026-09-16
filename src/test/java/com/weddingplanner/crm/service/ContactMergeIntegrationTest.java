package com.weddingplanner.crm.service;

import java.util.*;
import com.weddingplanner.crm.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import su.onno.crm.repository.*;
import su.onno.crm.service.CrmContactService;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:merge-test;DB_CLOSE_DELAY=-1","planner.marketing.demo-history=false", "planner.crm.demo-threads=false", "planner.events.demo=false"})
@Transactional
class ContactMergeIntegrationTest {
    @Autowired ContactMergeService merge;
    @Autowired ContactRepository contacts;
    @Autowired LeadInquiryRepository leads;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired CrmContactService crm;
    private final UsernamePasswordAuthenticationToken manager=new UsernamePasswordAuthenticationToken("demo@weddingplanner.local","",List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));

    @Test void combinesChannelsAndPreservesInquiryAttributionAndRouting() {
        var all=leads.findAllActive();var source=all.get(0);var target=all.get(1);
        var sourceId=source.getContact().id();var targetId=target.getContact().id();
        var channel=source.getChannel();var campaign=source.getCampaign();var origin=source.getSource();
        var before=conversations.findByCustomerAndDeletionMarkFalse(sourceId).getFirst();
        var inbox=before.getInbox();var channelKey=before.getChannel();
        var existingMessages=messages.findAllActive().stream().map(m->m.getId()).toList();
        merge.merge(sourceId,targetId,manager);
        // Twelve cards from the six seeded couples and five from the planner roster, one merged away.
        assertThat(contacts.findAllActive()).hasSize(16);
        assertThat(contacts.findById(sourceId).orElseThrow().isDeletionMark()).isTrue();
        assertThat(crm.canonical(sourceId)).isEqualTo(targetId);
        var moved=conversations.findActiveById(before.getId()).orElseThrow();
        assertThat(moved.getCustomer()).isEqualTo(targetId);
        assertThat(moved.getInbox()).isEqualTo(inbox);assertThat(moved.getChannel()).isEqualTo(channelKey);
        var inquiry=leads.findActiveById(source.getId()).orElseThrow();
        assertThat(inquiry.getContact().id()).isEqualTo(targetId);
        assertThat(inquiry.getSource()).isEqualTo(origin);assertThat(inquiry.getCampaign()).isEqualTo(campaign);assertThat(inquiry.getChannel()).isEqualTo(channel);
        assertThat(messages.findAllActive().stream().map(m->m.getId())).containsAll(existingMessages);
        assertThat(conversations.findByCustomerAndDeletionMarkFalse(targetId)).hasSize(2);
        int count=messages.findAllActive().size();merge.merge(sourceId,targetId,manager);
        assertThat(messages.findAllActive()).hasSize(count);
    }
    private com.weddingplanner.crm.domain.Contact card(String name, String email, String phone, com.weddingplanner.crm.domain.InboxFolder folder) {
        var contact=new com.weddingplanner.crm.domain.Contact();
        contact.setDescription(name);contact.setEmail(email);contact.setPhone(phone);contact.setInboxFolder(folder);
        return contacts.save(contact);
    }

    @Test void previewReportsOnlyTheFieldsTheCardsDisagreeOn() {
        var a=card("Amelia & Noah","amelia@example.com","+441234000001",com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        var b=card("Amelia and Noah","","+441234000002",com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        var preview=merge.preview(List.of(a.getId(),b.getId()),manager);
        assertThat(preview.cards()).extracting(ContactMergeService.Card::id).containsExactly(a.getId(),b.getId());
        var byKey=preview.fields().stream().collect(java.util.stream.Collectors.toMap(ContactMergeService.Field::key,f->f));
        assertThat(byKey.get("description").conflict()).isTrue();
        assertThat(byKey.get("phone").conflict()).isTrue();
        // One card left the email blank, so there is nothing to decide — a blank never competes.
        assertThat(byKey.get("email").conflict()).isFalse();
        assertThat(byKey.get("email").options()).extracting(ContactMergeService.Option::value).containsExactly("amelia@example.com");
        assertThat(byKey.get("inboxFolder").conflict()).isFalse();
    }

    @Test void keepsTheChosenValueEvenWhenItComesFromAnArchivedCard() {
        var keepCard=card("Olivia & James","stale@example.com","+441234000010",com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        var second=card("Olivia and James","olivia@example.com","+441234000011",com.weddingplanner.crm.domain.InboxFolder.PARTNERS);
        var third=card("O & J","","+441234000012",com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        merge.merge(List.of(second.getId(),third.getId()),keepCard.getId(),
            Map.of("description","Olivia & James","email","olivia@example.com","phone","+441234000012",
                   "inboxFolder","PARTNERS"),manager);
        var kept=contacts.findActiveById(keepCard.getId()).orElseThrow();
        assertThat(kept.getDescription()).isEqualTo("Olivia & James");
        assertThat(kept.getEmail()).isEqualTo("olivia@example.com");
        assertThat(kept.getPhone()).isEqualTo("+441234000012");
        assertThat(kept.getInboxFolder()).isEqualTo(com.weddingplanner.crm.domain.InboxFolder.PARTNERS);
        assertThat(contacts.findById(second.getId()).orElseThrow().isDeletionMark()).isTrue();
        assertThat(contacts.findById(third.getId()).orElseThrow().isDeletionMark()).isTrue();
        assertThat(crm.canonical(second.getId())).isEqualTo(keepCard.getId());
        assertThat(crm.canonical(third.getId())).isEqualTo(keepCard.getId());
    }

    @Test void ignoresTheKeptCardInTheSelectionAndIsSafeToRetry() {
        var keepCard=card("Sophia & Daniel","sophia@example.com","+441234000020",com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        var duplicate=card("Sophia and Daniel","","+441234000021",com.weddingplanner.crm.domain.InboxFolder.CLIENTS);
        // The selection sent from the list contains the kept card; that entry must be a no-op.
        merge.merge(List.of(keepCard.getId(),duplicate.getId()),keepCard.getId(),Map.of(),manager);
        assertThat(contacts.findActiveById(keepCard.getId())).isPresent();
        assertThat(contacts.findById(duplicate.getId()).orElseThrow().isDeletionMark()).isTrue();
        int count=messages.findAllActive().size();
        merge.merge(List.of(keepCard.getId(),duplicate.getId()),keepCard.getId(),Map.of(),manager);
        assertThat(messages.findAllActive()).hasSize(count);
        assertThatThrownBy(()->merge.merge(List.of(keepCard.getId()),keepCard.getId(),Map.of(),manager))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsSelfMergeAndReadOnlyUserWithoutChangingData() {
        var all=contacts.findAllActive();var first=all.get(0).getId();var second=all.get(1).getId();
        assertThatThrownBy(()->merge.merge(first,first,manager)).isInstanceOf(IllegalArgumentException.class);
        var viewer=new UsernamePasswordAuthenticationToken("viewer","",List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
        assertThatThrownBy(()->merge.merge(first,second,viewer)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(contacts.findAllActive()).hasSize(17);
        assertThat(crm.canonical(first)).isEqualTo(first);
    }
}
