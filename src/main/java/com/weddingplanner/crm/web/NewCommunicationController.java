package com.weddingplanner.crm.web;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;
import su.onno.ui.CurrentUserResolver;
import su.onno.ui.UiAccessService;

@RestController
@RequestMapping("/api/planner/communications")
public class NewCommunicationController {
    private final ContactRepository contacts;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final InboxRepository inboxes;
    private final CrmInboxWorkspaceService workspaces;
    private final CrmWorkspaceService guard;
    private final CrmContactService crm;
    private final UiAccessService access;
    private final CurrentUserResolver users;
    private final boolean readOnly;

    public NewCommunicationController(ContactRepository contacts, ConversationRepository conversations,
            ConversationMessageRepository messages, InboxRepository inboxes, CrmInboxWorkspaceService workspaces,
            CrmWorkspaceService guard, CrmContactService crm, UiAccessService access, CurrentUserResolver users,
            @Value("${onno.ui.read-only:false}") boolean readOnly) {
        this.contacts=contacts; this.conversations=conversations; this.messages=messages; this.inboxes=inboxes;
        this.workspaces=workspaces; this.guard=guard; this.crm=crm; this.access=access; this.users=users; this.readOnly=readOnly;
    }
    // The gate is workspace write access, and a logged call can be with anyone — so it checks the
    // inbox that carries every conversation, not the clients-only pipeline.
    private static final String WORKSPACE = "roles";

    private void authorize(Principal principal) {
        workspaces.requireWorkspace(WORKSPACE, principal, true);
        if (readOnly || !access.canWrite(principal,"catalog","Contacts"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Contact write access required");
    }
    static String digits(String phone) {
        String value=phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return value.startsWith("00") ? value.substring(2) : value;
    }
    private String phone(String raw) {
        if (raw==null || raw.length()>80 || !raw.matches("[+0-9() .-]+") ||
                !(raw.strip().startsWith("+") || raw.strip().startsWith("00")) ||
                digits(raw).length()<7 || digits(raw).length()>15)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Enter a phone number with country code, such as +44 7700 900123");
        return "+"+digits(raw);
    }
    private List<Contact> matching(String normalized) {
        return contacts.findAllActive().stream().filter(c -> digits(c.getPhone()).equals(digits(normalized))).toList();
    }
    public record Match(UUID id, String name) {}
    @GetMapping("/matches")
    public List<Match> matches(@RequestParam String phone, Principal principal) {
        authorize(principal);
        return matching(phone(phone)).stream().filter(c -> crm.canWrite(c.getId(),principal)).map(c -> new Match(c.getId(), c.getDescription())).toList();
    }
    public record Input(UUID requestId, String name, String phone, String notes, UUID contactId) {}
    public record Result(UUID contactId, UUID conversationId, UUID activityId) {}
    @PostMapping @Transactional
    public Result create(@RequestBody Input input, Principal principal) {
        authorize(principal);
        String normalized=phone(input.phone());
        if(input.requestId()==null || input.name()==null || input.name().isBlank() || input.name().length()>200 ||
                input.notes()==null || input.notes().isBlank() || input.notes().length()>7000)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Enter the caller's name and call notes (up to 7000 characters)");
        guard.lock();
        // A retry of the same form submission must not create a second contact or call.
        var previous=messages.findActiveById(input.requestId());
        if(previous.isPresent()) {
            var event=previous.get();
            if(!("planner-call:"+input.requestId()).equals(event.getExternalMessageId()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a new submission ID");
            var chat=workspaces.requireConversation(WORKSPACE,event.getConversation().id(),principal,true);
            workspaces.requireCustomer(chat.getCustomer(),principal,true);
            return new Result(chat.getCustomer(),chat.getId(),event.getId());
        }
        var matches=matching(normalized);
        Contact contact;
        if(input.contactId()!=null) {
            contact=matches.stream().filter(c->c.getId().equals(input.contactId())).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Phone number no longer matches this contact"));
        } else if(matches.size()>1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Several contacts share this number. Choose the caller before saving.");
        } else if(matches.size()==1) contact=matches.getFirst();
        else {
            contact=new Contact();contact.setDescription(input.name().strip());contact.setPhone(normalized);
            contacts.save(contact);
        }
        if(!crm.canWrite(contact.getId(),principal))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Contact write access required");
        var inbox=inboxes.findAllActive().stream().filter(i -> "planner-manual-calls".equals(i.getAddress())).findFirst().orElseGet(()->{
            var i=new Inbox();i.setDescription("Phone calls");i.setChannel(Channel.PHONE);i.setAddress("planner-manual-calls");return inboxes.save(i);
        });
        UUID customer=contact.getId();
        var conversation=conversations.findAllActive().stream().filter(c -> customer.equals(c.getCustomer()) &&
                c.getInbox()!=null && inbox.getId().equals(c.getInbox().id())).findFirst().orElseGet(()->{
            var c=new Conversation();c.setCustomer(customer);c.setInbox(Ref.of(Inbox.class,inbox.getId()));
            c.setChannel(Channel.PHONE);c.setSubject("Phone calls");c.setDescription("Phone calls");return c;
        });
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setLastMessagePreview(input.notes().strip().substring(0,Math.min(500,input.notes().strip().length())));
        conversations.save(conversation);
        var event=new ConversationMessage();event.setId(input.requestId());
        event.setConversation(Ref.of(Conversation.class,conversation.getId()));event.setChannel(Channel.PHONE);
        event.setKind(MessageKind.SYSTEM_EVENT);event.setDirection(MessageDirection.INTERNAL);
        event.setAuthorName(users.resolve(principal).displayName());event.setDescription("Call completed");
        event.setBody("Call completed\n"+input.notes().strip());event.setExternalMessageId("planner-call:"+input.requestId());
        event.setDeliveryStatus(DeliveryStatus.NOT_APPLICABLE);messages.save(event);
        return new Result(customer,conversation.getId(),event.getId());
    }
}
