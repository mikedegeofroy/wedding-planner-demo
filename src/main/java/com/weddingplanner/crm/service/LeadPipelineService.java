package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.repository.ConversationRepository;
import su.onno.events.EntityChangedEvent;
import su.onno.types.Ref;
import su.onno.ui.UiEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The client pipeline behind the Clients inbox. A couple's stage lives on their inquiry document —
 * the same field the Lead intelligence page reports on — so the inbox reads and moves that, rather
 * than keeping a second pipeline of its own.
 */
@Service
public class LeadPipelineService {
    private final LeadInquiryRepository leads;
    private final ConversationRepository conversations;
    private final UiEventPublisher events;
    private final PipelineStageService stages;

    private final LeadIndex index;

    public LeadPipelineService(LeadInquiryRepository leads, ConversationRepository conversations,
            UiEventPublisher events, PipelineStageService stages, LeadIndex index) {
        this.leads = leads;
        this.conversations = conversations;
        this.events = events;
        this.stages = stages;
        this.index = index;
    }

    /**
     * The stage each contact currently sits at. A contact with several inquiries takes the furthest
     * one along the pipeline, so an older lost inquiry can't drag a booked couple backwards.
     */
    public Map<UUID, Ref<PipelineStage>> stageByContact() {
        // The Clients folders are the stages, so filing one page of conversations asks this for
        // every couple at once. It is two columns of every inquiry and nothing else, which is what
        // the index reads — loading the documents to answer it cost a query per inquiry.
        return index.current().stageByContact();
    }

    /** The inquiry a conversation's stage moves, or empty when that couple has none yet. */
    public Optional<LeadInquiry> inquiryFor(UUID conversationId) {
        return conversations.findActiveById(conversationId)
                .map(conversation -> conversation.getCustomer())
                .flatMap(customer -> customer == null ? Optional.empty()
                        : Optional.ofNullable(index.current().leadByContact().get(customer))
                                .flatMap(leads::findActiveById));
    }

    /**
     * Move the couple behind this conversation to {@code stage}. Returns the inquiry that moved, or
     * empty when there is no inquiry to move — the caller reports that rather than inventing one.
     */
    @Transactional
    public Optional<LeadInquiry> moveToStage(UUID conversationId, Ref<PipelineStage> stage) {
        return inquiryFor(conversationId).map(lead -> {
            lead.setStage(stage);
            var saved = leads.save(lead);
            // The stage just moved, so the memoised index is now behind the database.
            index.invalidate();
            // The Clients inbox folders ARE the stages, so a move re-files the conversation. The CRM
            // only republishes its inbox for conversation/contact changes — an inquiry edit is ours
            // to announce.
            events.publish(EntityChangedEvent.UPDATED, "page", "crm-inbox-workspaces", null);
            return saved;
        });
    }
}
