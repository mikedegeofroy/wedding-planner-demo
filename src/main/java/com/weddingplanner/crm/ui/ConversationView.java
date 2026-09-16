package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.service.LeadPipelineService;
import com.weddingplanner.crm.service.PipelineStageService;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Conversation;
import su.onno.types.Ref;
import su.onno.ui.*;

/**
 * Pipeline moves from inside a conversation. One action per stage, so the inbox's stage control is
 * an ordinary host action the CRM executes through
 * {@code /api/crm/inbox-workspaces/{workspace}/conversations/{id}/actions} — the CRM starter owns
 * no pipeline of its own.
 *
 * <p>The stage list is a catalog the planning team edits, so these are declared through
 * {@link ActionSpec#dynamic} and resolved when the menu opens. Freezing them at startup would leave
 * a stage they added this morning missing from the menu until the next restart.</p>
 */
@Component
public class ConversationView implements EntityView<Conversation> {
    private final LeadPipelineService pipeline;
    private final PipelineStageService stages;

    public ConversationView(LeadPipelineService pipeline, PipelineStageService stages) {
        this.pipeline = pipeline;
        this.stages = stages;
    }

    public Class<Conversation> entity() { return Conversation.class; }

    public void actions(ActionSpec actions) {
        actions.dynamic(live -> {
            for (PipelineStage stage : stages.ordered()) {
                String label = stage.getDescription();
                Ref<PipelineStage> target = Ref.of(PipelineStage.class, stage.getId());
                live.action(moveActionKey(stage)).label("Move to " + label).icon("flag")
                        // The stage's own colour, so the host's menu shows the same swatches the
                        // pipeline pills and the StagePicker popover use.
                        .color(stage.getColor())
                        .scope(ActionScope.ROW).menu("Move to stage").roles("MANAGER", "ADMIN")
                        .handler(context -> pipeline.moveToStage(context.id(), target)
                                .map(lead -> ActionResult.refresh(ActionToast.success(
                                        lead.getCoupleName() + " moved to " + label)))
                                .orElseGet(() -> ActionResult.toast(ActionToast.warning(
                                        "No inquiry for this contact yet — open their card to create one."))));
            }
        });
    }

    /** The action key that files a client into this stage; the stage picker asks for it by name. */
    public static String moveActionKey(PipelineStage stage) {
        return "moveToStage_" + stage.getId().toString().replace("-", "");
    }
}
