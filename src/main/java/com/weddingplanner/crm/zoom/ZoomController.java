package com.weddingplanner.crm.zoom;

import com.weddingplanner.crm.repository.ContactRepository;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.types.Ref;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;

/**
 * Scheduling side of the planner's Zoom workspace. A meeting is created here and lands in the
 * conversation as an internal event, which is what the inbox timeline draws its Zoom card from.
 *
 * <p>The invitation is deliberately <em>not</em> sent from here: sending to a client is the chat's
 * own send, with its channel, its account and its delivery status, so the widget posts the
 * invitation through {@code /api/crm/conversations/{id}/messages} exactly as a typed reply would.</p>
 */
@RestController
@RequestMapping("/api/planner/zoom")
public class ZoomController {

    /** Every conversation is reachable from this inbox, and a meeting can be with anyone. */
    private static final String WORKSPACE = "roles";
    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH);

    private final ZoomWorkspace zoom;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ContactRepository contacts;
    private final CrmInboxWorkspaceService workspaces;
    private final CurrentUserResolver users;
    private final UiAccessService access;
    private final boolean readOnly;

    public ZoomController(ZoomWorkspace zoom, ConversationRepository conversations,
            ConversationMessageRepository messages, ContactRepository contacts,
            CrmInboxWorkspaceService workspaces, CurrentUserResolver users, UiAccessService access,
            @Value("${onno.ui.read-only:false}") boolean readOnly) {
        this.zoom = zoom; this.conversations = conversations; this.messages = messages;
        this.contacts = contacts; this.workspaces = workspaces; this.users = users;
        this.access = access; this.readOnly = readOnly;
    }

    @GetMapping("/account")
    public ZoomWorkspace.Account account(Principal principal) {
        workspaces.requireAccess(principal, false);
        return zoom.account(users.resolve(principal).displayName());
    }

    public record Schedule(UUID requestId, UUID conversationId, String topic, String startAt,
                           Integer minutes, String timezone, Boolean waitingRoom, Boolean recording) {}

    /** The meeting as the widget reads it — {@code meetingId} pre-grouped, invitation pre-worded. */
    public record View(String eventId, String conversationId, String topic, String meetingId,
                       long meetingNumber, String passcode, String joinUrl, String startAt, int minutes,
                       String timezone, boolean waitingRoom, boolean recording, String hostName,
                       String hostEmail, String invitation) {
        static View of(ZoomMeeting meeting) {
            return new View(meeting.eventId().toString(), meeting.conversationId().toString(), meeting.topic(),
                    meeting.meetingId(), meeting.meetingNumber(), meeting.passcode(), meeting.joinUrl(),
                    STORED.format(meeting.startAt()), meeting.minutes(), meeting.timezone(),
                    meeting.waitingRoom(), meeting.recording(), meeting.hostName(), meeting.hostEmail(),
                    meeting.invitation());
        }
    }

    @PostMapping("/meetings")
    @Transactional
    public View schedule(@RequestBody Schedule request, Principal principal) {
        workspaces.requireWorkspace(WORKSPACE, principal, true);
        if (readOnly || !access.canWrite(principal, "catalog", "Contacts"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Contact write access required");
        if (request == null || request.requestId() == null || request.conversationId() == null)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a conversation before scheduling");
        var conversation = workspaces.requireConversation(WORKSPACE, request.conversationId(), principal, true);

        // A retried submission must return the meeting it already created, not book a second one.
        var existing = messages.findActiveById(request.requestId());
        if (existing.isPresent()) {
            var event = existing.get();
            if (!("zoom-meeting:" + request.requestId()).equals(event.getExternalMessageId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a new submission ID");
            return View.of(ZoomMeeting.parse(event.getId(), conversation.getId(), event.getBody())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "That event is not a Zoom meeting")));
        }

        int minutes = request.minutes() == null ? 30 : request.minutes();
        if (minutes < 10 || minutes > 480)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A meeting runs between 10 and 480 minutes");
        LocalDateTime startAt = startAt(request.startAt());
        String zone = zone(request.timezone());
        String topic = topic(request.topic(), conversation);
        var user = users.resolve(principal);
        var meeting = zoom.schedule(request.requestId(), request.requestId(), conversation.getId(), topic,
                startAt, minutes, zone, !Boolean.FALSE.equals(request.waitingRoom()),
                Boolean.TRUE.equals(request.recording()), zoom.host(user.displayName()));

        var event = new ConversationMessage();
        event.setId(request.requestId());
        event.setConversation(Ref.of(Conversation.class, conversation.getId()));
        event.setChannel(conversation.getChannel());
        event.setKind(MessageKind.SYSTEM_EVENT);
        event.setDirection(MessageDirection.INTERNAL);
        event.setAuthorName(user.displayName());
        event.setDescription("Meeting planned");
        event.setBody(meeting.body());
        event.setExternalMessageId("zoom-meeting:" + request.requestId());
        event.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);
        messages.save(event);
        return View.of(meeting);
    }

    private LocalDateTime startAt(String value) {
        LocalDateTime startAt;
        if (value == null || value.length() < 16)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a date and time for the meeting");
        try {
            startAt = LocalDateTime.parse(value.substring(0, 16), STORED);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a date and time for the meeting");
        }
        var now = LocalDateTime.now();
        if (startAt.isBefore(now.minusMinutes(5)))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a time in the future");
        if (startAt.isAfter(now.plusYears(2)))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a time within the next two years");
        return startAt;
    }

    private String zone(String value) {
        if (value == null || value.isBlank()) return ZoneId.systemDefault().getId();
        try {
            return ZoneId.of(value.strip()).getId();
        } catch (RuntimeException e) {
            return ZoneId.systemDefault().getId();
        }
    }

    /** A blank topic becomes the one Zoom would default to: a meeting with whoever is in the chat. */
    private String topic(String value, Conversation conversation) {
        if (value != null && !value.isBlank())
            return value.strip().length() > 200 ? value.strip().substring(0, 200) : value.strip();
        String name = conversation.getCustomer() == null ? null : contacts.findActiveById(conversation.getCustomer())
                .map(contact -> contact.getDescription()).filter(text -> text != null && !text.isBlank()).orElse(null);
        return name == null ? "Wedding Planner meeting" : name + " · Wedding Planner";
    }
}
