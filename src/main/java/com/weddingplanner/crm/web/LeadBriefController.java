package com.weddingplanner.crm.web;

import com.weddingplanner.crm.service.LeadFacts;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.service.PipelineStageService;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.domain.Conversation;
import su.onno.crm.domain.ConversationMessage;
import su.onno.crm.domain.MessageDirection;
import su.onno.crm.domain.MessageKind;
import su.onno.crm.repository.ConversationMessageRepository;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.CrmInboxWorkspaceService;
import su.onno.types.Ref;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a manager needs to know before writing the next message, read across <b>every</b> channel the
 * couple has used rather than the one chat that happens to be open: where they stand, what is
 * outstanding, and what to do about it.
 *
 * <p>Everything here is derived from records the app already keeps — the inquiry, its touch history
 * and the messages on the couple's conversations. Nothing is generated: {@code summary} is the
 * inquiry's own stored narrative, and {@code next} is a rules read of the same fields the pipeline
 * qualifies on, so the brief cannot say something the records do not.</p>
 */
@RestController
@RequestMapping("/api/planner/brief")
public class LeadBriefController {
    /** The inbox this card belongs to; reading a brief requires access to that workspace. */
    private static final String WORKSPACE = "clients";

    /** A wedding this close with nothing signed is the thing to act on today. */
    private static final int DATE_PRESSURE_DAYS = 120;

    /** Familiar channel keys, so the card names a channel the way the couple would. */
    private static final Map<String, String> CHANNEL_LABELS = Map.of(
            "EMAIL", "Email", "WHATSAPP", "WhatsApp", "INSTAGRAM", "Instagram",
            "TELEGRAM", "Telegram", "WEB_CHAT", "Website form", "PHONE", "Phone");

    private final CrmInboxWorkspaceService workspaces;
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final LeadInquiryRepository leads;
    private final PipelineStageService stages;

    public LeadBriefController(CrmInboxWorkspaceService workspaces, ConversationRepository conversations,
            ConversationMessageRepository messages, LeadInquiryRepository leads,
            PipelineStageService stages) {
        this.workspaces = workspaces;
        this.conversations = conversations;
        this.messages = messages;
        this.leads = leads;
        this.stages = stages;
    }

    /** Where the couple stands, drawn in the stage's own colour. */
    public record Stage(String key, String label, String color) {}

    /**
     * The wedding itself. {@code inDays} is negative once the date has passed, and null when the
     * couple has not named one — which is itself something the brief asks the manager to fix.
     */
    public record Wedding(LocalDate date, Long inDays, Integer guests, String location, String budget,
                          String budgetBand) {}

    /**
     * One channel the couple has used. {@code lastDirection} is INBOUND when they wrote last and
     * OUTBOUND when we did, which is what makes a channel read as answered or outstanding.
     */
    public record ChannelLine(String channel, String label, String account, int messages,
                              LocalDateTime lastAt, String lastDirection, String lastPreview, int unread) {}

    /**
     * What is happening right now. {@code waitingOnUs} is the whole point of the card: it is true
     * when the couple's last word across every channel came after ours.
     */
    public record Status(boolean waitingOnUs, LocalDateTime since, String waitingFor, String headline,
                         LocalDateTime lastInbound, LocalDateTime lastOutbound, int touches) {}

    /** @param summary the inquiry's stored narrative — a record, not something generated here */
    public record Brief(String client, Stage stage, String qualification, String qualificationColor,
                        String owner, String summary, String wishes, Wedding wedding, Status status,
                        List<ChannelLine> channels, List<String> next) {}

    @GetMapping("/{conversation}")
    @Transactional(readOnly = true)
    public Brief brief(@PathVariable UUID conversation, Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Conversation opened = workspaces.requireConversation(WORKSPACE, conversation, principal, false);
        UUID customer = opened.getCustomer();
        if (customer == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This chat has no contact yet");

        // Every chat this couple has that the reader is allowed to see. A brief that silently skipped
        // a channel would be worse than no brief: it would report an answered client as waiting.
        List<Conversation> theirs = conversations.findByCustomerAndDeletionMarkFalse(customer).stream()
                .filter(c -> workspaces.canAccess(c, principal, false))
                .sorted(Comparator.comparing(Conversation::getLastMessageAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        LeadInquiry lead = LeadFacts.furthestByContact(leads, stages).get(customer);
        List<ChannelLine> channels = channelLines(theirs);
        Status status = status(theirs, lead);
        return new Brief(
                lead == null ? opened.getSubject() : lead.getCoupleName(),
                lead == null ? null : stage(lead),
                lead == null ? null : LeadFacts.label(lead.getQualification()),
                lead == null ? null : LeadFacts.color(lead.getQualification()),
                lead == null ? null : LeadFacts.label(lead.getOwner()),
                lead == null ? null : blankToNull(lead.getAiSummary()),
                lead == null ? null : blankToNull(lead.getOriginalMessage()),
                lead == null ? null : wedding(lead),
                status,
                channels,
                next(lead, status));
    }

    /** Empty when the lead sits in a stage the team has since deleted — the brief says so instead. */
    private Stage stage(LeadInquiry lead) {
        return stages.find(lead.getStage())
                .map(stage -> new Stage(stage.getId().toString(), stage.getDescription(), stage.getColor()))
                .orElse(null);
    }

    private static Wedding wedding(LeadInquiry lead) {
        LocalDate date = lead.getWeddingDate();
        return new Wedding(date,
                date == null ? null : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date),
                lead.getGuestCount(), blankToNull(lead.getPreferredLocation()),
                LeadFacts.euros(lead.getBudget()),
                lead.getBudgetBand() == null ? null : LeadFacts.label(lead.getBudgetBand()));
    }

    /** One line per channel, with several accounts on the same channel rolled into that channel. */
    private List<ChannelLine> channelLines(List<Conversation> theirs) {
        var byChannel = new LinkedHashMap<String, ChannelLine>();
        for (Conversation conversation : theirs) {
            String channel = conversation.getChannel() == null ? "" : conversation.getChannel();
            var thread = messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                    Ref.of(Conversation.class, conversation.getId())).stream()
                    .filter(message -> message.getKind() != MessageKind.SYSTEM_EVENT).toList();
            ConversationMessage last = thread.isEmpty() ? null : thread.get(thread.size() - 1);
            var line = new ChannelLine(channel,
                    CHANNEL_LABELS.getOrDefault(channel.toUpperCase(Locale.ROOT), channel),
                    conversation.getSubject(), thread.size(),
                    last == null ? conversation.getLastMessageAt() : last.getSentAt(),
                    last == null ? null : last.getDirection().name(),
                    preview(last == null ? conversation.getLastMessagePreview() : last.getBody()),
                    conversation.getUnreadCount());
            byChannel.merge(channel, line, LeadBriefController::merge);
        }
        return byChannel.values().stream()
                .sorted(Comparator.comparing(ChannelLine::lastAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Two chats on one channel read as one line: their messages add up, the newer one speaks last. */
    private static ChannelLine merge(ChannelLine current, ChannelLine incoming) {
        boolean incomingIsNewer = current.lastAt() == null
                || (incoming.lastAt() != null && incoming.lastAt().isAfter(current.lastAt()));
        ChannelLine newer = incomingIsNewer ? incoming : current;
        return new ChannelLine(current.channel(), current.label(), newer.account(),
                current.messages() + incoming.messages(), newer.lastAt(), newer.lastDirection(),
                newer.lastPreview(), current.unread() + incoming.unread());
    }

    /**
     * Who owes whom a message, across every channel at once. A couple who wrote on Instagram after
     * we answered their email is waiting, however tidy the email thread looks.
     */
    private Status status(List<Conversation> theirs, LeadInquiry lead) {
        LocalDateTime lastInbound = null;
        LocalDateTime lastOutbound = null;
        for (Conversation conversation : theirs)
            for (ConversationMessage message : messages.findByConversationAndDeletionMarkFalseOrderBySentAtAsc(
                    Ref.of(Conversation.class, conversation.getId()))) {
                if (message.getKind() == MessageKind.SYSTEM_EVENT) continue;
                if (message.getDirection() == MessageDirection.INBOUND)
                    lastInbound = later(lastInbound, message.getSentAt());
                else if (message.getDirection() == MessageDirection.OUTBOUND)
                    lastOutbound = later(lastOutbound, message.getSentAt());
            }
        boolean waitingOnUs = lastInbound != null && (lastOutbound == null || lastInbound.isAfter(lastOutbound));
        LocalDateTime since = waitingOnUs ? lastInbound : lastOutbound;
        String elapsed = since == null ? null : elapsed(since);
        String headline = lastInbound == null && lastOutbound == null ? "No messages on any channel yet"
                : waitingOnUs ? "Waiting on us for " + elapsed
                : "Waiting on the client — we replied " + elapsed + " ago";
        return new Status(waitingOnUs, since, waitingOnUs ? "us" : "the client", headline,
                lastInbound, lastOutbound, lead == null || lead.getTouchCount() == null ? 0 : lead.getTouchCount());
    }

    /**
     * What to do next, in the order it matters. The first three come from the qualification rules the
     * inquiry itself applies in {@code beforeWrite} — a lead is only qualified once the budget, the
     * date and the location are known — so the card asks for exactly what is blocking the stage.
     */
    private List<String> next(LeadInquiry lead, Status status) {
        var steps = new ArrayList<String>();
        if (status.waitingOnUs() && status.since() != null)
            steps.add("Reply — they have been waiting " + elapsed(status.since()));
        if (lead == null) {
            steps.add("Create the inquiry so this couple joins the pipeline");
            return steps;
        }
        // What to do next follows what a stage means, not what it is called: the team can rename
        // "Proposal / contract" or add a stage of their own without this advice going quiet.
        StageRole role = lead.getStageRole() == null ? StageRole.NEW : lead.getStageRole();
        if (role.lost()) {
            steps.add(lead.getLostReason() == null ? "Closed as lost" : "Closed as lost — " + LeadFacts.label(lead.getLostReason()));
            return steps;
        }
        var missing = new ArrayList<String>();
        if (lead.getBudget() == null) missing.add("the budget");
        if (lead.getWeddingDate() == null) missing.add("the date");
        if (lead.getPreferredLocation() == null || lead.getPreferredLocation().isBlank()) missing.add("the location");
        if (!missing.isEmpty()) steps.add("Ask for " + english(missing) + " — the lead cannot qualify without it");

        switch (role) {
            case QUALIFIED -> steps.add(lead.getMeetingAt() == null ? "Book the venue visit or a call"
                    : "Confirm the meeting on " + lead.getMeetingAt().toLocalDate());
            case MEETING -> steps.add(lead.getMeetingAt() != null && lead.getMeetingAt().isBefore(LocalDateTime.now())
                    ? "The meeting has happened — send the proposal" : "Prepare for the meeting");
            case PROPOSAL -> steps.add("Chase the contract and the deposit");
            case WON -> steps.add("Hand over to the event project");
            default -> { }
        }
        LocalDate date = lead.getWeddingDate();
        if (date != null && role != StageRole.WON) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date);
            if (days < 0) steps.add("The wedding date has passed with nothing booked — close or re-date the lead");
            else if (days <= DATE_PRESSURE_DAYS) steps.add("Only " + days + " days to the date — hold it or release it");
        }
        if (steps.isEmpty()) steps.add("Nothing outstanding — keep the conversation warm");
        return steps;
    }

    /** "and" between the last two, commas before that — the way a person would write the list. */
    private static String english(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** A duration at the resolution people use when chasing a reply: days, then hours, then minutes. */
    private static String elapsed(LocalDateTime since) {
        Duration gap = Duration.between(since, LocalDateTime.now());
        if (gap.isNegative()) return "moments";
        long days = gap.toDays();
        if (days > 0) return days + (days == 1 ? " day" : " days");
        long hours = gap.toHours();
        if (hours > 0) return hours + (hours == 1 ? " hour" : " hours");
        long minutes = Math.max(gap.toMinutes(), 1);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static LocalDateTime later(LocalDateTime current, LocalDateTime candidate) {
        return current == null || (candidate != null && candidate.isAfter(current)) ? candidate : current;
    }

    private static String preview(String body) {
        String text = blankToNull(body);
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 140 ? flat : flat.substring(0, 139) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
