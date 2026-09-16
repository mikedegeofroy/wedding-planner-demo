package com.weddingplanner.crm.web;

import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.service.LeadPipelineService;
import com.weddingplanner.crm.service.PipelineStageService;
import com.weddingplanner.crm.ui.ConversationView;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.crm.service.CrmInboxWorkspaceService;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Where a conversation's client sits in the pipeline, for the inbox's stage control. Moves still go
 * through the CRM's scoped action API ({@code moveToStage…} on {@link com.weddingplanner.crm.ui.ConversationView});
 * this only reports the current stage and the stages on offer, so the control can show them.
 */
@RestController
@RequestMapping("/api/planner/pipeline")
public class PipelineController {
    /** The inbox this control belongs to; reading a stage requires access to that workspace. */
    private static final String WORKSPACE = "clients";

    private final LeadPipelineService pipeline;
    private final CrmInboxWorkspaceService workspaces;
    private final PipelineStageService stages;

    public PipelineController(LeadPipelineService pipeline, CrmInboxWorkspaceService workspaces,
            PipelineStageService stages) {
        this.pipeline = pipeline;
        this.workspaces = workspaces;
        this.stages = stages;
    }

    /** One stage on offer. {@code action} is the host action key that moves a client into it. */
    public record Stage(String key, String label, String color, String action) {
        static Stage of(PipelineStage stage) {
            return new Stage(stage.getId().toString(), stage.getDescription(), stage.getColor(),
                    ConversationView.moveActionKey(stage));
        }
    }

    /**
     * @param current the client's stage, or null when they have no inquiry yet
     * @param next    the stage the forward arrow moves them to, or null at the end of the pipeline
     */
    public record Pipeline(Stage current, Stage next, String client, List<Stage> stages) {}

    @GetMapping("/{conversation}")
    @Transactional(readOnly = true)
    public Pipeline stage(@PathVariable UUID conversation, Principal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        workspaces.requireConversation(WORKSPACE, conversation, principal, false);
        var pipelineStages = stages.ordered().stream().map(Stage::of).toList();
        return pipeline.inquiryFor(conversation)
                .map(lead -> {
                    // A lead filed into a stage the team has since deleted reports no current stage
                    // rather than inventing one; the control still offers every stage on the list.
                    PipelineStage stage = stages.find(lead.getStage()).orElse(null);
                    return new Pipeline(stage == null ? null : Stage.of(stage),
                            stages.next(stage).map(Stage::of).orElse(null),
                            lead.getCoupleName(), pipelineStages);
                })
                .orElseGet(() -> new Pipeline(null, null, null, pipelineStages));
    }
}
