package com.weddingplanner.crm.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeadInquiryTest {

    /**
     * The stage list a lead is written against. The real one is a catalog the planning team edits;
     * these tests stand a small one up so the qualification rules can be exercised without a
     * database, and so a list that is <em>not</em> the default one gets exercised too.
     */
    private static final class Pipeline implements PipelineStageLookup {
        private final Map<StageRole, Integer> positions = new LinkedHashMap<>();

        Pipeline stage(StageRole role, int position) {
            positions.put(role, position);
            return this;
        }

        @Override
        public Optional<Ref<PipelineStage>> byRole(StageRole role) {
            return positions.containsKey(role)
                    ? Optional.of(Ref.of(PipelineStage.class, id(role))) : Optional.empty();
        }

        @Override
        public StageRole roleOf(Ref<PipelineStage> stage) {
            if (stage == null) return StageRole.CUSTOM;
            return positions.keySet().stream().filter(role -> id(role).equals(stage.id()))
                    .findFirst().orElse(StageRole.CUSTOM);
        }

        @Override
        public int positionOf(Ref<PipelineStage> stage) {
            return stage == null ? Integer.MAX_VALUE : positions.getOrDefault(roleOf(stage), Integer.MAX_VALUE);
        }

        static UUID id(StageRole role) {
            return UUID.nameUUIDFromBytes(("stage:" + role.name()).getBytes());
        }
    }

    /** The pipeline this business started with. */
    private static Pipeline defaultPipeline() {
        return new Pipeline().stage(StageRole.NEW, 0).stage(StageRole.QUALIFYING, 10)
                .stage(StageRole.QUALIFIED, 20).stage(StageRole.MEETING, 30)
                .stage(StageRole.PROPOSAL, 40).stage(StageRole.WON, 50).stage(StageRole.LOST, 60);
    }

    @BeforeEach
    void installPipeline() {
        PipelineStageLookup.Holder.install(defaultPipeline());
    }

    @AfterEach
    void clearPipeline() {
        PipelineStageLookup.Holder.install(null);
    }

    @Test
    void routesCompleteFiveHundredThousandEuroLeadToFounder() {
        LeadInquiry lead = new LeadInquiry();
        lead.setBudget(new BigDecimal("500000"));
        lead.setWeddingDate(LocalDate.of(2027, 6, 1));
        lead.setPreferredLocation("Lake Como");

        lead.beforeWrite();

        assertThat(lead.getQualification()).isEqualTo(Qualification.VIP);
        assertThat(lead.getOwner()).isEqualTo(LeadOwner.FOUNDER);
        assertThat(lead.getStageRole()).isEqualTo(StageRole.QUALIFIED);
        assertThat(lead.getStage().id()).isEqualTo(Pipeline.id(StageRole.QUALIFIED));
        assertThat(lead.isQualified()).isTrue();
        assertThat(lead.isVip()).isTrue();
    }

    @Test
    void keepsIncompleteLeadInQualification() {
        LeadInquiry lead = new LeadInquiry();
        lead.setBudget(new BigDecimal("800000"));

        lead.beforeWrite();

        assertThat(lead.getQualification()).isEqualTo(Qualification.NEEDS_INFORMATION);
        assertThat(lead.getOwner()).isEqualTo(LeadOwner.MANAGER);
        assertThat(lead.getStageRole()).isEqualTo(StageRole.QUALIFYING);
    }

    @Test
    void neverDragsALeadBackwardsThroughTheStagesItHasPassed() {
        LeadInquiry lead = new LeadInquiry();
        lead.setStage(Ref.of(PipelineStage.class, Pipeline.id(StageRole.PROPOSAL)));
        lead.setBudget(new BigDecimal("500000"));
        lead.setWeddingDate(LocalDate.of(2027, 6, 1));
        lead.setPreferredLocation("Lake Como");

        lead.beforeWrite();

        assertThat(lead.getStageRole()).isEqualTo(StageRole.PROPOSAL);
        assertThat(lead.isMet()).isTrue();
    }

    @Test
    void treatsAStageTheTeamAddedAsAnOrdinaryOpenStage() {
        // A stage of their own, sitting between the meeting and the proposal.
        PipelineStageLookup.Holder.install(defaultPipeline().stage(StageRole.CUSTOM, 35));
        LeadInquiry lead = new LeadInquiry();
        lead.setStage(Ref.of(PipelineStage.class, Pipeline.id(StageRole.CUSTOM)));
        lead.setBudget(new BigDecimal("400000"));
        lead.setWeddingDate(LocalDate.of(2027, 6, 1));
        lead.setPreferredLocation("Positano");

        lead.beforeWrite();

        assertThat(lead.getStageRole()).isEqualTo(StageRole.CUSTOM);
        assertThat(lead.isActive()).isTrue();
        assertThat(lead.isBooked()).isFalse();
        assertThat(lead.isLost()).isFalse();
        // It sits after the meeting stage, so the couple has been met.
        assertThat(lead.isMet()).isTrue();
    }

    @Test
    void readsWonAndLostFromWhatTheStageMeansRatherThanWhatItIsCalled() {
        LeadInquiry booked = new LeadInquiry();
        booked.setStage(Ref.of(PipelineStage.class, Pipeline.id(StageRole.WON)));
        booked.setBudget(new BigDecimal("600000"));
        booked.setWeddingDate(LocalDate.of(2027, 9, 4));
        booked.setPreferredLocation("Ravello");
        booked.beforeWrite();

        assertThat(booked.isBooked()).isTrue();
        assertThat(booked.isActive()).isFalse();
        assertThat(booked.getBookedAt()).isNotNull();

        LeadInquiry lost = new LeadInquiry();
        lost.setStage(Ref.of(PipelineStage.class, Pipeline.id(StageRole.LOST)));
        lost.setLostReason(LostReason.BUDGET);
        lost.beforeWrite();

        assertThat(lost.isLost()).isTrue();
        assertThat(lost.isActive()).isFalse();
        assertThat(lost.isMet()).isFalse();
        assertThat(lost.getLostReason()).isEqualTo(LostReason.BUDGET);
    }
}
