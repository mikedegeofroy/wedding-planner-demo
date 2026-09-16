package com.weddingplanner.crm.web;

import java.util.*;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:new-call-test;DB_CLOSE_DELAY=-1","planner.marketing.demo-history=false", "planner.crm.demo-threads=false", "planner.events.demo=false"})
@Transactional
class NewCommunicationIntegrationTest {
    @Autowired NewCommunicationController controller;
    @Autowired ContactRepository contacts;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    private final UsernamePasswordAuthenticationToken manager=new UsernamePasswordAuthenticationToken("demo@weddingplanner.local","",List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
    private NewCommunicationController.Input input(UUID id,String phone,UUID contact) {
        return new NewCommunicationController.Input(id,"New caller",phone,"Discussed dates and budget. Send venue options.",contact);
    }
    @Test void createsContactPhoneConversationAndCallTogetherAndRetriesOnlyOnce() {
        int before=contacts.findAllActive().size();
        var request=input(UUID.randomUUID(),"+44 (7700) 900123",null);
        var result=controller.create(request,manager);
        assertThat(contacts.findActiveById(result.contactId()).orElseThrow().getPhone()).isEqualTo("+447700900123");
        var chat=conversations.findActiveById(result.conversationId()).orElseThrow();
        assertThat(chat.getChannel()).isEqualTo(Channel.PHONE);
        assertThat(chat.getLastMessagePreview()).contains("Discussed dates");
        var message=messages.findActiveById(result.activityId()).orElseThrow();
        assertThat(message.getDirection()).isEqualTo(MessageDirection.INTERNAL);
        assertThat(message.getBody()).startsWith("Call completed\n");
        assertThat(controller.create(request,manager)).isEqualTo(result);
        assertThat(contacts.findAllActive()).hasSize(before+1);
        assertThat(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(su.onno.types.Ref.of(Conversation.class,chat.getId()))).hasSize(1);
    }
    @Test void reusesNormalizedPhoneWithoutChangingExistingName() {
        var existing=new Contact();existing.setDescription("Existing client");existing.setPhone("+44 7700-900124");contacts.save(existing);
        int before=contacts.findAllActive().size();
        var result=controller.create(input(UUID.randomUUID(),"0044 7700 900124",null),manager);
        assertThat(result.contactId()).isEqualTo(existing.getId());
        assertThat(contacts.findAllActive()).hasSize(before);
        assertThat(contacts.findActiveById(existing.getId()).orElseThrow().getDescription()).isEqualTo("Existing client");
        var again=controller.create(input(UUID.randomUUID(),"+447700900124",existing.getId()),manager);
        assertThat(again.conversationId()).isEqualTo(result.conversationId());
    }
    @Test void ambiguousNumberRequiresChoosingAContact() {
        var first=new Contact();first.setDescription("First");first.setPhone("+447700900125");contacts.save(first);
        var second=new Contact();second.setDescription("Second");second.setPhone(first.getPhone());contacts.save(second);
        assertThat(controller.matches(first.getPhone(),manager)).hasSize(2);
        assertThatThrownBy(()->controller.create(input(UUID.randomUUID(),first.getPhone(),null),manager)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsUnauthorizedAndInvalidRequestsWithoutCreatingRecords() {
        int before=contacts.findAllActive().size();
        var viewer=new UsernamePasswordAuthenticationToken("viewer","",List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
        assertThatThrownBy(()->controller.create(input(UUID.randomUUID(),"+447700900126",null),viewer)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.create(input(UUID.randomUUID(),"7700900126",null),manager)).isInstanceOf(ResponseStatusException.class);
        assertThat(contacts.findAllActive()).hasSize(before);
    }
}
