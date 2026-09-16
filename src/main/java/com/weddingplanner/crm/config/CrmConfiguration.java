package com.weddingplanner.crm.config;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.events.domain.EventParty;
import com.weddingplanner.crm.events.domain.EventStage;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import su.onno.crm.repository.ConversationRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.service.ContactFolderIndex;
import com.weddingplanner.crm.service.LeadFacts;
import com.weddingplanner.crm.service.LeadPipelineService;
import com.weddingplanner.crm.service.PipelineStageService;
import org.springframework.context.annotation.*;
import su.onno.crm.domain.Conversation;
import su.onno.crm.service.*;
import su.onno.types.Ref;
import su.onno.ui.*;
import su.onno.crm.domain.Channel;
import su.onno.crm.repository.InboxRepository;

/**
 * Two inboxes, because Wedding Planner works two different ways. The <b>Clients</b> inbox is the sales
 * pipeline: couples only, foldered by the stage their inquiry sits in, and a conversation moves
 * between folders by moving the inquiry. The <b>Projects</b> inbox is the delivery side: the same
 * conversations seen either by the role the counterparty plays (clients, contractors, venues…), by the event
 * they belong to — so one wedding's couple, florist and venue read as one folder — or as one flat
 * list of everything, which is where a chat no role and no event can file still shows up.
 */
@Configuration
public class CrmConfiguration {
    /** The CRM caps a workspace at 30 folders; leave room for the catch-all. */
    private static final int EVENT_FOLDER_LIMIT = 25;

    /**
     * Panel fields that only mean something for a couple in the sales pipeline. The Conversations
     * inbox reads the same contacts as contractors, venues and crew, where a wedding date or a
     * pipeline stage would be an empty row at best and someone else's wedding at worst.
     */
    private static final Set<String> CLIENT_ONLY_FIELDS = Set.of("customer.aiSummary", "customer.stage",
            "customer.qualification", "customer.owner", "customer.weddingDate", "customer.guestCount",
            "customer.preferredLocation", "customer.budget", "customer.budgetBand", "customer.meetingAt",
            "customer.leadChannel", "customer.source", "customer.campaign", "customer.firstTouchAt");

    /**
     * The contact fields the inbox's right-hand panel shows. Beyond the ways to reach someone, a
     * couple's panel carries the three things every conversation in this business is actually about
     * — <b>when</b> the wedding is, <b>what</b> they asked for, and <b>how much</b> they have — plus
     * where they stand in the pipeline. Those come from the couple's inquiry rather than the contact
     * record, so the panel and the Lead intelligence page always report the same numbers.
     *
     * <p>They are filed into sections so the panel can group them, and the pipeline ones hand over
     * the stage's own colour so it reads as the same pill it does everywhere else.</p>
     */
    @Bean
    CrmCustomerBinding<Contact> plannerContacts(ContactRepository contacts, LeadInquiryRepository leads,
            PipelineStageService stages) {
        // One panel read asks for ten fields, and every one of them wants the couple's inquiry.
        // Resolving them through a shared, briefly-memoised lookup is one scan of the inquiries per
        // panel rather than ten, while a stage moved a moment ago still shows on the next breath.
        var lead = LeadFacts.live(leads, stages);
        var catalog = new CrmCatalogBinding<>(Contact.class, contacts::findActiveById)
                .field("email", "Email", "email", Contact::getEmail)
                .field("phone", "Phone", "phone", Contact::getPhone)
                .field("stage", "Stage", "Pipeline", "badge",
                        contact -> lead.apply(contact).map(inquiry -> stages.label(inquiry.getStage())).orElse(null))
                .color("stage", contact -> lead.apply(contact).map(inquiry -> stages.color(inquiry.getStage())).orElse(null))
                .field("qualification", "Qualification", "Pipeline", "badge",
                        contact -> lead.apply(contact).map(inquiry -> LeadFacts.label(inquiry.getQualification())).orElse(null))
                .color("qualification", contact -> lead.apply(contact).map(inquiry -> LeadFacts.color(inquiry.getQualification())).orElse(null))
                .field("owner", "Owner", "Pipeline", "text",
                        contact -> lead.apply(contact).map(inquiry -> LeadFacts.label(inquiry.getOwner())).orElse(null))
                .field("weddingDate", "Wedding date", "The wedding", "date",
                        contact -> lead.apply(contact).map(LeadInquiry::getWeddingDate).orElse(null))
                .field("guestCount", "Guests", "The wedding", "number",
                        contact -> lead.apply(contact).map(LeadInquiry::getGuestCount).orElse(null))
                .field("preferredLocation", "Location", "The wedding", "text",
                        contact -> lead.apply(contact).map(LeadInquiry::getPreferredLocation).orElse(null))
                .field("budget", "Budget", "The wedding", "text",
                        contact -> lead.apply(contact).map(inquiry -> LeadFacts.euros(inquiry.getBudget())).orElse(null))
                .field("budgetBand", "Budget band", "The wedding", "badge",
                        contact -> lead.apply(contact).map(inquiry -> LeadFacts.label(inquiry.getBudgetBand())).orElse(null))
                .color("budgetBand", contact -> lead.apply(contact).map(inquiry -> LeadFacts.color(inquiry.getBudgetBand())).orElse(null))
                .field("meetingAt", "Meeting", "Pipeline", "date",
                        contact -> lead.apply(contact).map(LeadInquiry::getMeetingAt).orElse(null))
                // Where a couple came from. A form submission cannot be replied to, so attribution is
                // the only thing that panel row can usefully say about the channel they arrived on;
                // the ways to actually reach them are the identities above.
                .field("leadChannel", "Came in via", "Where they came from", "badge",
                        contact -> lead.apply(contact).map(inquiry -> LeadFacts.label(inquiry.getChannel())).orElse(null))
                .color("leadChannel", contact -> lead.apply(contact).map(inquiry -> LeadFacts.color(inquiry.getChannel())).orElse(null))
                .field("source", "Source", "Where they came from", "badge",
                        contact -> lead.apply(contact).map(inquiry -> LeadFacts.label(inquiry.getSource())).orElse(null))
                .color("source", contact -> lead.apply(contact).map(inquiry -> LeadFacts.color(inquiry.getSource())).orElse(null))
                .field("campaign", "Campaign", "Where they came from", "text",
                        contact -> lead.apply(contact).map(CrmConfiguration::campaignOf).orElse(null))
                .field("firstTouchAt", "First touch", "Where they came from", "date",
                        contact -> lead.apply(contact).map(LeadInquiry::getFirstTouchAt).orElse(null))
                .field("aiSummary", "AI summary", "text", contact -> lead.apply(contact)
                        .map(LeadInquiry::getAiSummary)
                        .filter(summary -> summary != null && !summary.isBlank())
                        .orElse("No inquiry summary yet."))
                .field("inboxFolder", "Inbox folder", "text", contact -> (contact.getInboxFolder() == null ? InboxFolder.CLIENTS : contact.getInboxFolder()).label());
        // Unknown channel identities become distinct contacts; never merge by name/email.
        return new CrmCustomerBinding<>(catalog, incoming -> {
            var contact = new Contact();
            contact.setDescription(incoming.name() == null || incoming.name().isBlank()
                    ? "New inquiry" : incoming.name());
            contact.setEmail(incoming.email());
            contact.setPhone(incoming.phone());
            return Ref.of(Contact.class, contacts.save(contact).getId());
        });
    }

    /**
     * Inbox one — the client pipeline. Only conversations with couples, foldered by the pipeline
     * stage of their inquiry; {@link LeadPipelineService} moves a client between those folders.
     */
    @Bean
    CrmInboxWorkspace clientsInbox(InboxRepository inboxes, ContactFolderIndex contactFolders,
            LeadPipelineService pipeline, PipelineStageService stages, ConversationRepository conversations) {
        return new CrmInboxWorkspace("clients", "Clients", Set.of("MANAGER"), Set.of("MANAGER"),
                conversation -> contactFolders.of(conversation.getCustomer()) == InboxFolder.CLIENTS,
                config -> {
                    contactFolders.refresh();
                    // The folders ARE the stage list, so adding, renaming or reordering a stage
                    // re-folders this inbox on the next read without touching this code.
                    Map<UUID, Ref<PipelineStage>> filed = pipeline.stageByContact();
                    var clients = conversations.findAllActive().stream()
                            .filter(c -> contactFolders.of(c.getCustomer()) == InboxFolder.CLIENTS).toList();
                    var folders = new ArrayList<CrmWorkspaceService.Folder>();
                    var placed = new HashSet<UUID>();
                    for (PipelineStage stage : stages.ordered()) {
                        var ids = clients.stream()
                                .filter(c -> filed.get(c.getCustomer()) != null
                                        && stage.getId().equals(filed.get(c.getCustomer()).id()))
                                .map(Conversation::getId).toList();
                        placed.addAll(ids);
                        folders.add(folder(stageFolderKey(stage), stage.getDescription(), ids)
                                .withIcon(stageGlyph(stage.getRole()), stage.getColor()));
                    }
                    // Whatever the stages did not claim, by subtraction rather than by asking again
                    // whether a couple has an inquiry. Both conditions have to land here: a couple
                    // who wrote in and has no inquiry yet, and one whose inquiry points at a stage
                    // that no longer exists. Testing only for the first is how a deleted stage takes
                    // its chats out of every folder while they keep counting in the total — visible
                    // as a run of loose conversations between the folders and nowhere to file them.
                    var unfiled = clients.stream().map(Conversation::getId)
                            .filter(id -> !placed.contains(id)).toList();
                    folders.add(folder("stage_none", "No inquiry yet", unfiled)
                            .withIcon("circle-dashed", "#64748B"));
                    // The narrative belongs to the brief card in the same panel, under "Where it
                    // stands" — repeating it as a field would print the paragraph twice.
                    return plannerActions(config.withFields(config.fields().stream()
                            .filter(field -> !field.key().equals("customer.aiSummary")).toList())
                            .withFolders(folders));
                })
                .list(list -> conversationList(list, inboxes, true));
    }

    /**
     * Inbox two, mode one — every conversation by the role its counterparty plays. This is the
     * folder set the single Wedding Planner inbox used to carry.
     */
    @Bean
    CrmInboxWorkspace rolesInbox(InboxRepository inboxes, ContactFolderIndex contactFolders,
            ConversationRepository conversations) {
        return new CrmInboxWorkspace("roles", "By role", Set.of("MANAGER"), Set.of("MANAGER"), c -> true,
                config -> {
                    contactFolders.refresh();
                    var all = conversations.findAllActive();
                    return plannerActions(config.withFields(clientFieldsRemoved(config))
                            .withFolders(Arrays.stream(InboxFolder.values()).map(folder -> {
                                var ids = all.stream()
                                        .filter(c -> contactFolders.of(c.getCustomer()) == folder)
                                        .map(Conversation::getId).toList();
                                return folder(folder.name().toLowerCase(Locale.ROOT), folder.label(), ids)
                                        .withIcon(folder.glyph(), LeadFacts.color(folder));
                            }).toList()));
                })
                .list(list -> conversationList(list, inboxes, false));
    }

    /**
     * Inbox two, mode two — one folder per event, holding everyone working on it: the couple, the
     * contractors, the venue. Membership comes from the event's client plus its participants, so a
     * contact who works three weddings appears under all three.
     */
    @Bean
    CrmInboxWorkspace projectsInbox(InboxRepository inboxes, ConversationRepository conversations,
            EventProjectRepository events, EventPartyRepository parties) {
        // This mode answers "who is working on which wedding", so a chat attached to no event has
        // nothing to say here — membership is the union of the folders below, not every chat.
        // All chats still carries the whole inbox.
        return new CrmInboxWorkspace("projects", "By event", Set.of("MANAGER"), Set.of("MANAGER"),
                memberOfAnEvent(events, parties),
                config -> {
                    var all = conversations.findAllActive();
                    var participants = parties.findAllActive();
                    var folders = new ArrayList<CrmWorkspaceService.Folder>();
                    // The CRM allows 30 folders, so only the nearest events get one. A conversation
                    // outside them is not listed in this mode; All chats still carries every chat.
                    for (EventProject event : foldered(events)) {
                        var people = new HashSet<UUID>();
                        if (event.getClient() != null) people.add(event.getClient().id());
                        participants.stream()
                                .filter(party -> party.getEvent() != null && event.getId().equals(party.getEvent().id()))
                                .map(EventParty::getContact).filter(java.util.Objects::nonNull)
                                .forEach(contact -> people.add(contact.id()));
                        var ids = all.stream().filter(c -> people.contains(c.getCustomer()))
                                .map(Conversation::getId).toList();
                        // Folder keys allow [a-z][a-z0-9_]{0,39} — a UUID's dashes do not qualify.
                        folders.add(folder("event_" + event.getId().toString().replace("-", ""),
                                eventLabel(event), ids)
                                .withIcon(eventGlyph(event.getStage()), LeadFacts.color(event.getStage())));
                    }
                    return plannerActions(config.withFields(clientFieldsRemoved(config)).withFolders(folders));
                })
                .list(list -> conversationList(list, inboxes, false));
    }

    /**
     * Inbox two, mode three — the catch-all. Every active conversation in one flat list, including
     * the ones no role and no event can file: a chat whose contact carries no folder, or whose
     * counterparty works on nothing yet. No folders at all, so nothing can drop out of it.
     */
    @Bean
    CrmInboxWorkspace allChatsInbox(InboxRepository inboxes) {
        return new CrmInboxWorkspace("all", "All chats", Set.of("MANAGER"), Set.of("MANAGER"), c -> true,
                config -> plannerActions(config.withFields(clientFieldsRemoved(config)).withFolders(List.of())))
                .list(list -> conversationList(list, inboxes, false));
    }

    /**
     * The CRM's own <i>Schedule call</i> and <i>Log activity</i> buttons, switched off. Scheduling
     * in this app goes through the Zoom workspace, whose scheduler sits in the same chat header,
     * and a second button next to it offering to paste a meeting URL by hand is a different answer
     * to the same question. Logging a call that happened somewhere else is not lost: the planner's
     * own button carries it, writing the same internal event through the same endpoint.
     */
    private static CrmWorkspaceService.Config plannerActions(CrmWorkspaceService.Config config) {
        return config.withActions(config.actions().stream()
                .map(action -> action.key().equals("logActivity")
                        ? new CrmWorkspaceService.Action(action.key(), action.label(), false) : action)
                .toList());
    }

    /** The display fields left once the pipeline-only ones are dropped. */
    private static List<CrmWorkspaceService.DisplayField> clientFieldsRemoved(CrmWorkspaceService.Config config) {
        return config.fields().stream().filter(field -> !CLIENT_ONLY_FIELDS.contains(field.key())).toList();
    }

    /** The events that get a folder: the nearest {@link #EVENT_FOLDER_LIMIT}, newest start first. */
    private static List<EventProject> foldered(EventProjectRepository events) {
        return events.findAllActive().stream()
                .sorted(java.util.Comparator.comparing(EventProject::getStartDate,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(EVENT_FOLDER_LIMIT).toList();
    }

    /**
     * The workspace's membership test. {@code selection} is asked once per conversation as the
     * inbox lists, and again on every single-conversation read, so the contact set is memoised for
     * a second: one list render computes it once, while a contact just added to an event still
     * reaches their chat on the next breath rather than after a cache expiry.
     */
    private static java.util.function.Predicate<Conversation> memberOfAnEvent(
            EventProjectRepository events, EventPartyRepository parties) {
        var cached = new java.util.concurrent.atomic.AtomicReference<Map.Entry<Long, Set<UUID>>>();
        return conversation -> {
            if (conversation.getCustomer() == null) return false;
            long now = System.nanoTime();
            var snapshot = cached.get();
            if (snapshot == null || now - snapshot.getKey() > 1_000_000_000L) {
                snapshot = Map.entry(now, eventContacts(events, parties));
                cached.set(snapshot);
            }
            return snapshot.getValue().contains(conversation.getCustomer());
        };
    }

    /**
     * Every contact reachable from a foldered event — its client and its participants. Membership
     * is drawn from the same window the folders use, so the list never carries a chat that no
     * folder can hold.
     */
    private static Set<UUID> eventContacts(EventProjectRepository events, EventPartyRepository parties) {
        var people = new HashSet<UUID>();
        var foldered = foldered(events).stream().map(EventProject::getId).collect(java.util.stream.Collectors.toSet());
        for (EventProject event : foldered(events))
            if (event.getClient() != null) people.add(event.getClient().id());
        parties.findAllActive().stream()
                .filter(party -> party.getEvent() != null && foldered.contains(party.getEvent().id()))
                .map(EventParty::getContact).filter(java.util.Objects::nonNull)
                .forEach(contact -> people.add(contact.id()));
        return people;
    }

    /** The pipeline inbox: couples, foldered by stage. */
    @Bean
    Page inboxPage() {
        return new Page() {
            public String route() { return "/inbox"; }
            public void compose(PageBuilder page) {
                page.header(false);
                // The folders here ARE the pipeline, and a stage is not a reading preference, so
                // the Folders/Everything switch is off: there is no useful "ungrouped" view of a
                // sales pipeline, and offering one invites a manager to lose the stage they work in.
                page.widget("Clients").type("crmInboxWorkspaces").width("full")
                        .config("workspace", "clients").config("channelFilters", "true")
                        .config("accountFilter", "false").config("grouping", "false");
            }
        };
    }

    /** The delivery inbox: the same conversations, read by role, by event, or all at once. */
    @Bean
    Page projectsInboxPage() {
        return new Page() {
            public String route() { return "/conversations"; }
            public void compose(PageBuilder page) {
                page.header(false);
                // No channel bar here: this inbox switches by role/event/all, and the channel filter
                // stays available in the list's own filter row.
                page.widget("Conversations").type("crmInboxWorkspaces").width("full")
                        .config("workspace", "roles").config("modes", "roles:users,projects:party-popper,all:messages-square")
                        .config("channelFilters", "false").config("accountFilter", "false");
            }
        };
    }

    /**
     * The columns and sort both inboxes share. {@code channelFilters} declares the channel/account
     * facets: the Clients inbox shows them as the channel bar above the list, while the
     * Conversations inbox folds by role or event (or not at all) and carries no channel controls.
     */
    private void conversationList(ListSpec<Conversation> list, InboxRepository inboxes, boolean channelFilters) {
        list.title("Conversations");
        list.columns(Conversation::getCustomer, Conversation::getChannel, Conversation::getInbox, Conversation::getSubject,
                Conversation::getLastMessageAt, Conversation::getUnreadCount);
        list.sortBy(Conversation::getLastMessageAt, true);
        list.custom("crmInbox").label("Inbox").defaultView();
        list.label(Conversation::getInbox, "Account");
        if (!channelFilters) return;
        // No website entry: a form is where a couple came from, not somewhere a planner can write.
        // Their inquiry opens on the address the form captured, and "Website form" stays where it
        // belongs — the "Came in via" badge on the panel and the attribution charts.
        var channels = new LinkedHashMap<String, String>();
        channels.put(Channel.INSTAGRAM, "Instagram");
        channels.put(Channel.WHATSAPP, "WhatsApp");
        channels.put(Channel.EMAIL, "Email");
        channels.put(Channel.PHONE, "Phone calls");
        var accounts = new LinkedHashMap<String, String>();
        inboxes.findAllActive().stream().filter(account -> account.isActive())
                .sorted(java.util.Comparator.comparing(account -> account.getDescription()))
                .forEach(account -> {
                    channels.putIfAbsent(account.getChannel(), account.getChannel());
                    accounts.put(account.getId().toString(), account.getDescription()
                            + " · " + account.getAddress());
                });
        list.filter(Conversation::getChannel).label("Channel").options(channels);
        list.filter(Conversation::getInbox).label("Account").options(accounts);
    }

    /** A folder that stays visible while empty, instead of matching the whole inbox. */
    /** Folder keys allow {@code [a-z][a-z0-9_]} only, and a stage is identified by its id. */
    static String stageFolderKey(PipelineStage stage) {
        return "stage_" + stage.getId().toString().replace("-", "");
    }

    /** The campaign a couple arrived on, falling back to the raw utm_source when it carries no name. */
    private static String campaignOf(LeadInquiry inquiry) {
        for (String candidate : List.of(
                inquiry.getCampaign() == null ? "" : inquiry.getCampaign(),
                inquiry.getUtmSource() == null ? "" : inquiry.getUtmSource())) {
            if (!candidate.isBlank()) return candidate;
        }
        return null;
    }

    /**
     * The glyph a stage shows in the inbox, chosen by what the stage <em>means</em> rather than by
     * what it is called — the planning team renames stages, and a folder that changed its icon
     * because someone reworded a label would be a puzzle. The colour is the stage's own.
     */
    private static String stageGlyph(StageRole role) {
        return switch (role == null ? StageRole.CUSTOM : role) {
            case NEW -> "sparkles";
            case QUALIFYING -> "search";
            case QUALIFIED -> "badge-check";
            case MEETING -> "calendar-check";
            case PROPOSAL -> "file-text";
            case WON -> "party-popper";
            case LOST -> "circle-x";
            case DISQUALIFIED -> "user-x";
            case CUSTOM -> "folder";
        };
    }

    /**
     * Where a wedding has got to, as its folder's glyph. Keyed on the stage rather than on the
     * event's name for the same reason the pipeline folders are: a calendar that has been confirmed
     * reads differently from one still being argued over, and neither depends on what the event was
     * called. The hue is the stage's own, so a folder matches the badge on its project card.
     */
    private static String eventGlyph(EventStage stage) {
        return switch (stage == null ? EventStage.PLANNING : stage) {
            case PLANNING -> "calendar-clock";
            case CONFIRMED -> "calendar-check";
            case COMPLETED -> "party-popper";
            case CANCELLED -> "calendar-x";
        };
    }

    private static CrmWorkspaceService.Folder folder(String key, String label, List<UUID> ids) {
        return ids.isEmpty() ? CrmWorkspaceService.Folder.empty(key, label)
                : CrmWorkspaceService.Folder.conversations(key, label, ids);
    }

    private static String eventLabel(EventProject event) {
        String name = event.getDescription() == null || event.getDescription().isBlank()
                ? event.getCode() : event.getDescription();
        return event.getStartDate() == null ? name : name + " · " + event.getStartDate();
    }
}
