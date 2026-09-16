package com.weddingplanner.crm.service;

import java.security.Principal;
import com.weddingplanner.crm.events.repository.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.*;
import su.onno.crm.repository.*;
import su.onno.crm.service.*;
import su.onno.types.Ref;

/** Wedding Planner owns business consolidation; CRM retains every channel's original delivery routing. */
@Service
public class ContactMergeService {
    /** The contact attributes a merge has to reconcile, in the order the review dialog lists them. */
    public static final List<String> FIELDS = List.of("description", "email", "phone", "inboxFolder");

    private final ContactRepository contacts;
    private final LeadInquiryRepository leads;
    private final CrmContactService crm;
    private final CrmWorkspaceService guard;
    private final CrmInboxWorkspaceService workspaces;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final EventProjectRepository events;
    private final EventPartyRepository parties;
    private final EventBudgetRepository budgets;
    private final EventInvoiceRepository invoices;
    private final boolean readOnly;
    public ContactMergeService(ContactRepository contacts, LeadInquiryRepository leads, CrmContactService crm,
            CrmWorkspaceService guard, CrmInboxWorkspaceService workspaces, ConversationRepository conversations,
            ConversationMessageRepository messages, EventProjectRepository events, EventPartyRepository parties, EventBudgetRepository budgets, EventInvoiceRepository invoices, @Value("${onno.ui.read-only:false}") boolean readOnly) {
        this.contacts=contacts;this.leads=leads;this.crm=crm;this.guard=guard;this.workspaces=workspaces;
        this.events=events;this.parties=parties;this.budgets=budgets;this.invoices=invoices;
        this.conversations=conversations;this.messages=messages;this.readOnly=readOnly;
    }

    /** Read one reconcilable attribute as the string the review dialog and its choices use. */
    public static String value(Contact contact, String field) {
        return switch (field) {
            case "description" -> contact.getDescription();
            case "email" -> contact.getEmail();
            case "phone" -> contact.getPhone();
            case "inboxFolder" -> contact.getInboxFolder()==null ? null : contact.getInboxFolder().name();
            default -> throw new IllegalArgumentException("Unknown contact field: "+field);
        };
    }

    private static void apply(Contact contact, String field, String value) {
        switch (field) {
            case "description" -> contact.setDescription(value);
            case "email" -> contact.setEmail(value);
            case "phone" -> contact.setPhone(value);
            case "inboxFolder" -> {
                try { contact.setInboxFolder(InboxFolder.valueOf(value)); }
                catch (IllegalArgumentException e) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown inbox folder: "+value);
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown contact field: "+field);
        }
    }

    private static boolean blank(String value) { return value==null || value.isBlank(); }

    /**
     * Fold {@code sourceIds} into {@code targetId}, keeping the explicitly chosen attribute values.
     *
     * <p>{@code keep} maps a field from {@link #FIELDS} to the value the retained card must end up
     * with — that is how a conflict is resolved, and it is independent of which card is kept (keep
     * card A but take B's phone). A field left out of {@code keep} follows the historical rule: the
     * target's own value wins, and a blank target field is filled from the first source that has
     * one. Every source card is deletion-marked, and its conversations, inquiries, events, parties,
     * budget lines and unposted invoices are re-pointed at the retained card.</p>
     */
    @Transactional
    public void merge(List<UUID> sourceIds, UUID targetId, Map<String,String> keep, Principal principal) {
        if (targetId == null) throw new IllegalArgumentException("Choose the contact card to keep");
        var sources = new LinkedHashSet<>(sourceIds == null ? List.<UUID>of() : sourceIds);
        sources.remove(targetId); // The kept card may sit in the selection; that entry is a no-op.
        if (sources.isEmpty()) throw new IllegalArgumentException("Choose a different contact to merge");
        if (readOnly || principal == null || !crm.canWrite(targetId, principal))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Both contacts must be writable");
        for (var sourceId : sources)
            if (!crm.canWrite(sourceId, principal))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Both contacts must be writable");
        guard.lock();
        workspaces.requireAllCustomerConversations(targetId, principal);
        for (var sourceId : sources) workspaces.requireAllCustomerConversations(sourceId, principal);
        // Safe retry after a first request committed: a source already folded in is simply done.
        var pending = new ArrayList<UUID>();
        for (var sourceId : sources) if (!crm.canonical(sourceId).equals(targetId)) pending.add(sourceId);
        if (!crm.canonical(targetId).equals(targetId))
            throw new IllegalArgumentException("Choose the current, active contact cards");
        for (var sourceId : pending)
            if (!crm.canonical(sourceId).equals(sourceId))
                throw new IllegalArgumentException("Choose the current, active contact cards");
        if (pending.isEmpty()) return;
        var target = contacts.findActiveById(targetId)
            .orElseThrow(()->new IllegalArgumentException("Target contact is unavailable"));
        var resolved = new ArrayList<Contact>();
        for (var sourceId : pending) resolved.add(contacts.findActiveById(sourceId)
            .orElseThrow(()->new IllegalArgumentException("Source contact is unavailable")));
        // The reviewer's explicit choices win; anything they did not reconcile keeps the old
        // "target wins, blanks borrow from a source" behaviour so the row action is unchanged.
        if (keep != null) for (var field : FIELDS) {
            var chosen = keep.get(field);
            if (chosen != null && !chosen.isBlank()) apply(target, field, chosen);
        }
        for (var source : resolved) {
            if (blank(target.getEmail())) target.setEmail(source.getEmail());
            if (blank(target.getPhone())) target.setPhone(source.getPhone());
            if (target.getPartner() == null) target.setPartner(source.getPartner());
        }
        // A card never partners itself: folding one half of a couple into the other would otherwise
        // leave the survivor pointing at its own id.
        if (target.getPartner() != null && targetId.equals(target.getPartner().id())) target.setPartner(null);
        contacts.save(target);
        for (var source : resolved) fold(source, target, principal);
    }

    /** Re-point every business reference from one source card onto the retained card. */
    private void fold(Contact source, Contact target, Principal principal) {
        UUID sourceId=source.getId(), targetId=target.getId();
        crm.transferLinks(sourceId,targetId);
        // Include archived inquiries so their historical reference also points at the retained card.
        for (var lead:leads.findAll()) {
            if (lead.getContact()!=null && sourceId.equals(lead.getContact().id())) {
                lead.setContact(Ref.of(Contact.class,targetId));leads.save(lead);
            }
        }
        var retained=Ref.of(Contact.class,targetId);
        // Whoever named the source as their partner now names the retained card — and the retained
        // card does not become its own partner when the two halves of a couple are merged.
        for(var other:contacts.findAllActive())if(other.getPartner()!=null&&sourceId.equals(other.getPartner().id())){
            other.setPartner(other.getId().equals(targetId)?null:retained);contacts.save(other);
        }
        for(var event:events.findAllActive())if(event.getClient()!=null&&sourceId.equals(event.getClient().id())){event.setClient(retained);events.save(event);}
        for(var party:parties.findAllActive())if(party.getContact()!=null&&sourceId.equals(party.getContact().id())){party.setContact(retained);parties.save(party);}
        for(var budget:budgets.findAllActive()){
            boolean changed=false;
            for(var row:budget.getItems())if(row.getContractor()!=null&&sourceId.equals(row.getContractor().id())){row.setContractor(retained);changed=true;}
            if(changed)budgets.save(budget);
        }
        // Posted invoices retain the original financial party for audit; drafts follow the contact.
        for(var invoice:invoices.findAllActive())if(!invoice.isPosted()&&invoice.getCounterparty()!=null&&sourceId.equals(invoice.getCounterparty().id())){invoice.setCounterparty(retained);invoices.save(invoice);}
        // Keep the source row and all its original attributes available for audit/old references.
        source.setDeletionMark(true);contacts.save(source);
        var related=conversations.findByCustomerAndDeletionMarkFalse(targetId);
        if (!related.isEmpty()) {
            var conversation=related.getFirst();
            var event=new ConversationMessage();event.setConversation(Ref.of(Conversation.class,conversation.getId()));
            event.setChannel(conversation.getChannel());event.setKind(MessageKind.SYSTEM_EVENT);
            event.setDirection(MessageDirection.INTERNAL);event.setAuthorName(principal.getName());
            event.setDescription("Contacts merged");
            event.setBody("Contacts merged\n"+source.getDescription()+" → "+target.getDescription()
                +"\nOriginal contact: "+sourceId
                +"\nEmail: "+java.util.Objects.toString(source.getEmail(),"—")
                +"\nPhone: "+java.util.Objects.toString(source.getPhone(),"—"));
            messages.save(event);
        }
    }

    /** Fold one card into another, leaving every attribute conflict to the historical default. */
    @Transactional
    public void merge(UUID sourceId, UUID targetId, Principal principal) {
        if (sourceId!=null && sourceId.equals(targetId))
            throw new IllegalArgumentException("Choose a different contact to merge");
        merge(List.of(sourceId), targetId, Map.of(), principal);
    }

    /** What the review dialog shows for one candidate card. */
    public record Card(UUID id, String code, Map<String,String> values, int conversations, int inquiries) {}
    /** One competing value for a field, and the cards that carry it. */
    public record Option(String value, String label, List<UUID> contacts) {}
    /** A field to reconcile: {@code conflict} is true once two cards disagree on a non-blank value. */
    public record Field(String key, String label, boolean conflict, List<Option> options) {}
    /** Everything the dialog needs to let a reviewer choose what the merge keeps. */
    public record Preview(List<Card> cards, List<Field> fields, UUID suggestedTarget) {}

    private static final Map<String,String> LABELS = Map.of(
        "description", "Couple / name", "email", "Email", "phone", "Phone", "inboxFolder", "Inbox folder");

    private static String label(String field, String value) {
        if (value==null || value.isBlank()) return "—";
        if (!field.equals("inboxFolder")) return value;
        try { return InboxFolder.valueOf(value).label(); } catch (IllegalArgumentException e) { return value; }
    }

    /**
     * Describe the selected cards and where they disagree. Read-only: it resolves nothing and
     * writes nothing, so the dialog may open it as often as the reviewer reopens the review.
     */
    public Preview preview(List<UUID> ids, Principal principal) {
        var selected = new LinkedHashSet<>(ids == null ? List.<UUID>of() : ids);
        if (selected.size() < 2) throw new IllegalArgumentException("Select at least two contacts to merge");
        var cards = new ArrayList<Card>();
        for (var id : selected) {
            if (!crm.canWrite(id, principal))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Every selected contact must be writable");
            var contact = contacts.findActiveById(id)
                .orElseThrow(()->new IllegalArgumentException("A selected contact is no longer available"));
            var values = new LinkedHashMap<String,String>();
            for (var field : FIELDS) values.put(field, java.util.Objects.toString(value(contact, field), ""));
            int chats = conversations.findByCustomerAndDeletionMarkFalse(id).size();
            int inquiries = (int) leads.findAllActive().stream()
                .filter(lead -> lead.getContact()!=null && id.equals(lead.getContact().id())).count();
            cards.add(new Card(id, contact.getCode(), values, chats, inquiries));
        }
        var fields = new ArrayList<Field>();
        for (var field : FIELDS) {
            var byValue = new LinkedHashMap<String,List<UUID>>();
            for (var card : cards) {
                var raw = card.values().get(field);
                if (raw==null || raw.isBlank()) continue; // A blank card never competes for a field.
                byValue.computeIfAbsent(raw, key -> new ArrayList<>()).add(card.id());
            }
            var options = byValue.entrySet().stream()
                .map(e -> new Option(e.getKey(), label(field, e.getKey()), List.copyOf(e.getValue()))).toList();
            fields.add(new Field(field, LABELS.getOrDefault(field, field), options.size() > 1, options));
        }
        // Suggest keeping the card carrying the most history, then the most complete one.
        var suggested = cards.stream().max(java.util.Comparator
            .<Card>comparingInt(c -> c.conversations() + c.inquiries())
            .thenComparingInt(c -> (int) c.values().values().stream().filter(v -> !v.isBlank()).count()))
            .map(Card::id).orElseThrow();
        return new Preview(List.copyOf(cards), List.copyOf(fields), suggested);
    }
}
