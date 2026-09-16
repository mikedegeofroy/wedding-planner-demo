package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.repository.PipelineStageRepository;
import com.weddingplanner.crm.service.PipelineStageService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

/**
 * Takes the pipeline down to the five stages this business actually decides between, on a database
 * that was built with seven, and keeps every lead pointing at a stage that still exists.
 *
 * <p>"Qualifying", "Meeting booked" and "Proposal / contract" are retired: they were three folders
 * for one question, and the meeting and the proposal they marked are recorded as touchpoints, which
 * is where the funnel reads them from either way. "Lost" splits in two, because a couple the
 * business wanted and did not win is a different fact from one it was never going to take, and a
 * single rate over both says nothing.</p>
 *
 * <p>The second half of the job is the one that matters on every boot after the first. A lead whose
 * stage no longer resolves — retired here, or deleted by hand from the stage catalog — is not
 * merely mis-filed: the inbox folds by stage, so it belongs to no folder and its chats drop out of
 * the folder list entirely while still counting in the total. So rather than run once and trust the
 * result, this re-homes any such lead every boot, which makes it idempotent by construction: once
 * there are none, it does nothing.</p>
 */
// Straight after PipelineStageSeeder(-100), and so before anything that files a lead into a stage.
@Order(-99)
@Component
public class PipelineStageTrim implements CommandLineRunner {

    /** The stage constants this business no longer separates. Their leads are re-homed below. */
    private static final List<String> RETIRED = List.of("QUALIFYING", "MEETING_BOOKED", "PROPOSAL");

    /** The surviving list, in order. Positions are set from this so the new stage sorts correctly. */
    private static final List<String> ORDER = List.of("NEW", "QUALIFIED", "BOOKED", "LOST", "LOST_NOT_QUALIFIED");

    private final PipelineStageRepository stages;
    private final LeadInquiryRepository leads;
    private final PipelineStageService pipeline;

    public PipelineStageTrim(PipelineStageRepository stages, LeadInquiryRepository leads,
            PipelineStageService pipeline) {
        this.stages = stages; this.leads = leads; this.pipeline = pipeline;
    }

    @Override
    public void run(String... args) {
        // Retire first, then re-home. The other way round, a lead saved while its old stage is
        // still active is pulled straight back into it: the write hook advances an unqualified
        // lead to whatever stage plays "being qualified", which was the stage being retired.
        retire();
        rename("LOST", "Lost — qualified", "#DC2626");
        position();
        pipeline.invalidate();

        rehomeOrphans();
        splitLostLeads();
    }

    private void retire() {
        boolean any = false;
        for (String constant : RETIRED) {
            var stage = stages.findActiveById(PipelineStageService.legacyId(constant)).orElse(null);
            if (stage == null) continue;
            stage.setDeletionMark(true);
            stages.save(stage);
            any = true;
        }
        if (any) pipeline.invalidate();
    }

    /**
     * Every lead whose stage is not one of the stages that exist, moved to the one its own facts
     * put it in. {@code qualified} is derived on write from budget, date and location, so this reads
     * the business's own judgement rather than re-deciding it — which is also why it lands on the
     * same answer the three retired stages did: being qualified, having met and having been sent a
     * proposal were all "worth our time", and everything short of that is still to be worked out.
     */
    private void rehomeOrphans() {
        var toNew = pipeline.stageFor(StageRole.NEW).orElse(null);
        var toQualified = pipeline.stageFor(StageRole.QUALIFIED).orElse(null);
        if (toNew == null || toQualified == null) return;
        for (LeadInquiry lead : leads.findAllActive()) {
            if (lead.getStage() == null || pipeline.find(lead.getStage()).isPresent()) continue;
            PipelineStage target = lead.isQualified() ? toQualified : toNew;
            lead.setStage(Ref.of(PipelineStage.class, target.getId()));
            lead.setStageRole(target.getRole());
            leads.save(lead);
        }
    }

    /** A lost lead lands on the side its own qualification puts it. */
    private void splitLostLeads() {
        UUID lost = PipelineStageService.legacyId("LOST");
        var notAFit = stages.findActiveById(PipelineStageService.legacyId("LOST_NOT_QUALIFIED")).orElse(null);
        if (notAFit == null) return;
        for (LeadInquiry lead : leads.findAllActive()) {
            if (lead.getStage() == null || !lost.equals(lead.getStage().id()) || lead.isQualified()) continue;
            lead.setStage(Ref.of(PipelineStage.class, notAFit.getId()));
            lead.setStageRole(StageRole.DISQUALIFIED);
            leads.save(lead);
        }
    }

    private void rename(String constant, String label, String color) {
        stages.findActiveById(PipelineStageService.legacyId(constant)).ifPresent(stage -> {
            if (label.equals(stage.getDescription())) return;
            stage.setDescription(label);
            stage.setColor(color);
            stages.save(stage);
        });
    }

    /** Close the gaps the retired stages left, so the new one sorts into the list rather than onto it. */
    private void position() {
        for (int index = 0; index < ORDER.size(); index++) {
            int slot = index * 10;
            stages.findActiveById(PipelineStageService.legacyId(ORDER.get(index))).ifPresent(stage -> {
                if (stage.getPosition() != null && stage.getPosition() == slot) return;
                stage.setPosition(slot);
                stages.save(stage);
            });
        }
    }
}
