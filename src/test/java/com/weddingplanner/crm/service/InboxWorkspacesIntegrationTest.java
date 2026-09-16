package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import su.onno.crm.repository.ConversationRepository;
import su.onno.crm.service.CrmInboxWorkspace;
import su.onno.crm.service.CrmWorkspaceService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The two inboxes: the client pipeline, and the delivery view folded by role, by event, or not at all. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:inbox-workspaces;DB_CLOSE_DELAY=-1",
        "planner.events.demo=true", "planner.marketing.demo-history=false", "planner.crm.demo-threads=false"})
@Transactional
class InboxWorkspacesIntegrationTest {
    @Autowired @Qualifier("clientsInbox") CrmInboxWorkspace clients;
    @Autowired @Qualifier("projectsInbox") CrmInboxWorkspace projects;
    @Autowired @Qualifier("allChatsInbox") CrmInboxWorkspace all;
    @Autowired CrmWorkspaceService configuration;
    @Autowired LeadPipelineService pipeline;
    @Autowired PipelineStageService stages;
    @Autowired com.weddingplanner.crm.repository.PipelineStageRepository stageRepository;
    @Autowired LeadInquiryRepository leads;
    @Autowired ConversationRepository conversations;

    private List<CrmWorkspaceService.Folder> foldersOf(CrmInboxWorkspace workspace) {
        return workspace.configure().apply(configuration.get().config()).folders();
    }

    @Test
    void clientsInboxFoldersAreThePipelineStages() {
        var folders = foldersOf(clients);
        assertThat(folders).hasSize(stages.ordered().size() + 1); // stages, plus "No inquiry yet"
        // Five stages, not the seven this started with: one open question rather than three, and
        // the two losses told apart. See PipelineStageService.DEFAULTS.
        assertThat(folders.stream().map(CrmWorkspaceService.Folder::label))
                .containsExactly("New", "Qualified", "Booked", "Lost — qualified", "Lost — not a fit",
                        "No inquiry yet");
    }

    @Test
    void movingAClientRefilesTheirConversation() {
        var lead = leads.findAllActive().stream()
                .filter(l -> l.getContact() != null && l.getStageRole() != StageRole.WON)
                .findFirst().orElseThrow();
        var conversation = conversations.findAllActive().stream()
                .filter(c -> lead.getContact().id().equals(c.getCustomer()))
                .findFirst().orElseThrow();
        String from = folderFor(conversation.getId());

        var booked = stages.stageFor(StageRole.WON).orElseThrow();
        assertThat(pipeline.moveToStage(conversation.getId(), su.onno.types.Ref.of(PipelineStage.class,
                booked.getId()))).isPresent();

        assertThat(folderFor(conversation.getId())).isEqualTo(booked.getDescription()).isNotEqualTo(from);
    }

    @Test
    void aStageTheTeamAddsBecomesAFolderOfItsOwn() {
        int before = foldersOf(clients).size();
        var stage = new PipelineStage();
        stage.setDescription("Site visit booked");
        stage.setPosition(35);
        stage.setColor("#0D9488");
        stage.setRole(StageRole.CUSTOM);
        stageRepository.save(stage);
        stages.invalidate(); // the live app waits out the one-second memo instead

        var folders = foldersOf(clients);
        assertThat(folders).hasSize(before + 1);
        assertThat(folders.stream().map(CrmWorkspaceService.Folder::label))
                .contains("Site visit booked");
    }

    @Test
    void projectFoldersCarryEveryoneWorkingOnTheEventAndNothingElse() {
        var folders = foldersOf(projects);
        // Every folder is an event: a conversation outside them is simply not listed in this mode.
        assertThat(folders).isNotEmpty();
        assertThat(folders.stream().map(CrmWorkspaceService.Folder::label))
                .doesNotContain("Not on an event yet");
        // The seeded events each have a couple and their suppliers, so at least one event folder
        // holds conversations from more than one counterparty.
        assertThat(folders.stream().map(folder -> folder.conversationIds().size()))
                .anyMatch(size -> size > 1);
    }

    @Test
    void allChatsIsAFlatListThatHoldsEveryConversation() {
        assertThat(foldersOf(all)).isEmpty(); // no folders — nothing can fall outside one
        var everything = conversations.findAllActive();
        assertThat(everything).isNotEmpty();
        assertThat(everything.stream().filter(all.selection())).hasSameSizeAs(everything);
        // The point of the mode: chats the event folding cannot file are still reachable here.
        assertThat(everything.stream().filter(c -> !projects.selection().test(c))).isNotEmpty();
    }

    /** The folder a conversation currently files under, by label. */
    /**
     * The inbox folds by stage, so a lead whose stage has gone leaves its chats in no folder at all
     * — present in the total, absent from every folder, and unreachable in a foldered view. Every
     * conversation the workspace admits has to be in exactly one folder, whatever the stage list
     * has since had done to it.
     */
    @Test
    void aConversationWhoseStageWasDeletedStillLandsInAFolder() {
        var lead = leads.findAllActive().stream()
                .filter(l -> l.getContact() != null && l.getStage() != null)
                .findFirst().orElseThrow();
        var stage = stageRepository.findActiveById(lead.getStage().id()).orElseThrow();
        var stranded = conversations.findAllActive().stream()
                .filter(c -> lead.getContact().id().equals(c.getCustomer()))
                .map(su.onno.crm.domain.Conversation::getId).findFirst().orElseThrow();
        assertThat(folderFor(stranded)).isEqualTo(stage.getDescription());

        stage.setDeletionMark(true);
        stageRepository.save(stage);
        stages.invalidate();

        assertThat(folderFor(stranded)).isEqualTo("No inquiry yet");
        assertThat(foldersOf(clients).stream().mapToLong(f -> f.conversationIds().size()).sum())
                .isEqualTo(conversations.findAllActive().stream()
                        .filter(c -> clients.selection().test(c)).count());
    }

    private String folderFor(java.util.UUID conversation) {
        return foldersOf(clients).stream()
                .filter(folder -> folder.conversationIds().contains(conversation))
                .map(CrmWorkspaceService.Folder::label)
                .findFirst().orElse("");
    }
}
