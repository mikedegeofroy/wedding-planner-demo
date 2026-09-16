package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.PipelineStage;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

/**
 * The pipeline, as a list the planning team keeps. Adding a stage here adds a folder to the Clients
 * inbox, an entry to the move-to-stage menu and a column to the pipeline report; reordering them
 * reorders all three. What a stage <em>means</em> to the rules stays explicit in "Means".
 */
@Component
public class PipelineStageView implements EntityView<PipelineStage> {
    @Override
    public Class<PipelineStage> entity() { return PipelineStage.class; }

    @Override
    public void list(ListSpec<PipelineStage> spec) {
        spec.columns(PipelineStage::getPosition, PipelineStage::getDescription,
                        PipelineStage::getRole, PipelineStage::getColor)
                .label(PipelineStage::getDescription, "Stage")
                .label(PipelineStage::getPosition, "Order");
        spec.filter(PipelineStage::getRole).label("Means").multiOptions();
        // Read the pipeline the way it runs: first stage at the top.
        spec.sortBy(PipelineStage::getPosition);
    }

    @Override
    public void fields(EntityConfigBuilder<PipelineStage> fields) {
        fields.field(PipelineStage::getDescription).label("Stage").order(10).width("full")
                .placeholder("Qualifying");
        fields.field(PipelineStage::getPosition).order(20).width("half")
                .hint("Lowest first. Leave gaps so a new stage can be slipped in between two.");
        fields.field(PipelineStage::getColor).order(30).width("half").widget("color")
                .hint("The pill colour, everywhere this stage appears.");
        fields.field(PipelineStage::getRole).order(40).width("full")
                .hint("What the rules read. Keep one won stage and one lost stage; anything else "
                        + "the pipeline needs is an ordinary stage.");
    }
}
