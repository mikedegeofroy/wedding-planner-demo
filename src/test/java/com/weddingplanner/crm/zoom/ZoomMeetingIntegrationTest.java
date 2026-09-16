package com.weddingplanner.crm.zoom;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.types.Ref;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:zoom-test;DB_CLOSE_DELAY=-1",
        "planner.marketing.demo-history=false", "planner.crm.demo-threads=false", "planner.events.demo=false"})
@Transactional
class ZoomMeetingIntegrationTest {
    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH);

    @Autowired ZoomController controller;
    @Autowired ContactRepository contacts;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationMessageRepository messages;
    @Autowired InboxRepository inboxes;

    private final UsernamePasswordAuthenticationToken manager = new UsernamePasswordAuthenticationToken(
            "demo@weddingplanner.local", "", List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));

    private Conversation chat() {
        var contact = new Contact();
        contact.setDescription("Amelia & Noah");
        contacts.save(contact);
        var inbox = new Inbox();
        inbox.setDescription("Studio inbox");
        inbox.setChannel(Channel.EMAIL);
        inbox.setAddress("zoom-test@weddingplanner.local");
        inboxes.save(inbox);
        var conversation = new Conversation();
        conversation.setCustomer(contact.getId());
        conversation.setInbox(Ref.of(Inbox.class, inbox.getId()));
        conversation.setChannel(Channel.EMAIL);
        conversation.setSubject("Wedding enquiry");
        conversation.setDescription("Wedding enquiry");
        return conversations.save(conversation);
    }

    private ZoomController.Schedule request(UUID id, UUID conversation, String startAt, Integer minutes) {
        return new ZoomController.Schedule(id, conversation, "Intro call", startAt, minutes, "Europe/Rome", true, true);
    }

    private String inTwoDays() {
        return STORED.format(LocalDateTime.now().plusDays(2).withMinute(0).withSecond(0).withNano(0));
    }

    @Test void schedulesAMeetingAsAnInternalEventThatReadsBackUnchanged() {
        var conversation = chat();
        var id = UUID.randomUUID();
        var meeting = controller.schedule(request(id, conversation.getId(), inTwoDays(), 45), manager);

        assertThat(meeting.joinUrl()).matches("https://[a-z0-9.]+zoom\\.us/j/\\d{11}\\?pwd=[A-Za-z0-9]{22}");
        assertThat(meeting.meetingId()).matches("\\d{3} \\d{4} \\d{4}");
        assertThat(meeting.joinUrl()).contains(String.valueOf(meeting.meetingNumber()));
        // What the client is sent is two lines: when, and the link. Nothing else — the meeting id
        // and passcode are the planner's to read off the card, and the URL already carries them.
        assertThat(meeting.invitation().lines()).hasSize(2);
        assertThat(meeting.invitation()).endsWith(meeting.joinUrl()).contains("Europe/Rome")
                .doesNotContain("Meeting ID").doesNotContain("Passcode").doesNotContain("inviting you");
        // The signed-in principal here is a login, not a person, so the event records the studio
        // rather than demo@weddingplanner.local as the host.
        assertThat(meeting.hostName()).isEqualTo("Wedding Planner");

        var event = messages.findActiveById(id).orElseThrow();
        assertThat(event.getKind()).isEqualTo(MessageKind.SYSTEM_EVENT);
        assertThat(event.getDirection()).isEqualTo(MessageDirection.INTERNAL);
        assertThat(event.getBody()).startsWith("Meeting planned · Scheduled for ");

        var parsed = ZoomMeeting.parse(id, conversation.getId(), event.getBody()).orElseThrow();
        assertThat(parsed.meetingNumber()).isEqualTo(meeting.meetingNumber());
        assertThat(parsed.passcode()).isEqualTo(meeting.passcode());
        assertThat(parsed.joinUrl()).isEqualTo(meeting.joinUrl());
        assertThat(parsed.minutes()).isEqualTo(45);
        assertThat(parsed.timezone()).isEqualTo("Europe/Rome");
        assertThat(parsed.waitingRoom()).isTrue();
        assertThat(parsed.recording()).isTrue();
    }

    @Test void retriedSubmissionReturnsTheSameMeetingInsteadOfBookingASecond() {
        var conversation = chat();
        var request = request(UUID.randomUUID(), conversation.getId(), inTwoDays(), 30);
        var first = controller.schedule(request, manager);
        var second = controller.schedule(request, manager);
        assertThat(second).isEqualTo(first);
        assertThat(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversation.getId()))).hasSize(1);
    }

    @Test void refusesAPastTimeAnImpossibleDurationAndAnUnknownConversation() {
        var conversation = chat();
        assertThatThrownBy(() -> controller.schedule(
                request(UUID.randomUUID(), conversation.getId(), STORED.format(LocalDateTime.now().minusDays(1)), 30), manager))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.schedule(
                request(UUID.randomUUID(), conversation.getId(), inTwoDays(), 5), manager))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.schedule(
                request(UUID.randomUUID(), UUID.randomUUID(), inTwoDays(), 30), manager))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                Ref.of(Conversation.class, conversation.getId()))).isEmpty();
    }

    @Test void aBlankTopicBecomesTheContactInTheChat() {
        var conversation = chat();
        var meeting = controller.schedule(new ZoomController.Schedule(UUID.randomUUID(), conversation.getId(),
                "  ", inTwoDays(), 30, "Europe/Rome", null, null), manager);
        assertThat(meeting.topic()).isEqualTo("Amelia & Noah · Wedding Planner");
        assertThat(meeting.waitingRoom()).isTrue();
        assertThat(meeting.recording()).isFalse();
    }

    @Test void anEventThatIsNotAZoomMeetingDoesNotParseAsOne() {
        assertThat(ZoomMeeting.parse(UUID.randomUUID(), UUID.randomUUID(),
                "Call completed\nSpoke about the venue shortlist.")).isEmpty();
    }
}
