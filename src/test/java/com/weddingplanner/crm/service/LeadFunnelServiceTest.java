package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.LeadTouchpoint;
import com.weddingplanner.crm.domain.LostReason;
import com.weddingplanner.crm.domain.MarketingSource;
import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.domain.TouchType;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.repository.PipelineStageRepository;
import org.junit.jupiter.api.Test;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the funnel is allowed to claim. The rules worth pinning down are the two a funnel chart
 * usually gets wrong: a lead is counted at the furthest step it reached (so a late loss still
 * counts on every step it passed), and a lead that has not advanced is only "lost" when it actually
 * ended — otherwise it is work in progress.
 */
class LeadFunnelServiceTest {

    private static final List<StageRole> ORDER = List.of(StageRole.NEW, StageRole.QUALIFYING,
            StageRole.QUALIFIED, StageRole.MEETING, StageRole.PROPOSAL, StageRole.WON, StageRole.LOST);

    private static UUID stageId(StageRole role) {
        return UUID.nameUUIDFromBytes(("stage:" + role.name()).getBytes());
    }

    /** The pipeline this business started with, as the catalog would hand it back. */
    private static PipelineStageService stages() {
        PipelineStageRepository repository = mock(PipelineStageRepository.class);
        when(repository.findAllActive()).thenReturn(ORDER.stream().map(role -> {
            var stage = new PipelineStage();
            stage.setId(stageId(role));
            stage.setDescription(role.name());
            stage.setRole(role);
            stage.setPosition(ORDER.indexOf(role) * 10);
            return stage;
        }).toList());
        return new PipelineStageService(repository);
    }

    private static LeadFunnelService funnelOf(LeadInquiry... leads) {
        LeadInquiryRepository repository = mock(LeadInquiryRepository.class);
        when(repository.findAllActive()).thenReturn(List.of(leads));
        return new LeadFunnelService(repository, stages());
    }

    /** A lead as the app writes one: dated, sourced, with a budget and a stage it has been filed in. */
    private static LeadInquiry lead(String budget, StageRole role) {
        var lead = new LeadInquiry();
        lead.setDate(LocalDateTime.of(2026, 3, 1, 10, 0));
        lead.setSource(MarketingSource.META_ADS);
        lead.setBudget(new BigDecimal(budget));
        lead.setStage(Ref.of(PipelineStage.class, stageId(role)));
        lead.setStageRole(role);
        lead.setFirstResponseAt(LocalDateTime.of(2026, 3, 1, 10, 30));
        return lead;
    }

    private static LeadInquiry touched(LeadInquiry lead, TouchType type) {
        var touch = new LeadTouchpoint();
        touch.setAt(LocalDateTime.of(2026, 3, 2, 9, 0));
        touch.setType(type);
        lead.getTouches().add(touch);
        return lead;
    }

    private static long reached(LeadFunnelService.Funnel funnel, String key) {
        return funnel.rungs().stream().filter(rung -> rung.key().equals(key)).findFirst().orElseThrow().leads();
    }

    private static LeadFunnelService.Gap gap(LeadFunnelService.Funnel funnel, String from) {
        return funnel.gaps().stream().filter(value -> value.fromKey().equals(from)).findFirst().orElseThrow();
    }

    @Test
    void countsALostLeadOnEveryStepItPassedBeforeItWasLost() {
        var lost = touched(lead("600000", StageRole.LOST), TouchType.PROPOSAL_SENT);
        lost.setQualified(true);
        lost.setMet(true);
        lost.setLost(true);
        lost.setLostReason(LostReason.COMPETITOR);

        var funnel = funnelOf(lost).funnel(null, null, LeadFunnelService.Dimension.SOURCE);

        assertThat(reached(funnel, "inquiry")).isEqualTo(1);
        assertThat(reached(funnel, "qualified")).isEqualTo(1);
        assertThat(reached(funnel, "met")).isEqualTo(1);
        assertThat(reached(funnel, "proposal")).isEqualTo(1);
        assertThat(reached(funnel, "booked")).isZero();
        // It left at the last gap, and the reason it left is the one the team recorded.
        assertThat(gap(funnel, "proposal").lost()).isEqualTo(1);
        assertThat(gap(funnel, "proposal").reasons()).singleElement()
                .extracting(LeadFunnelService.Reason::label).isEqualTo("Chose a competitor");
    }

    @Test
    void readsALeadStillInPlayAsOpenRatherThanLost() {
        var open = lead("400000", StageRole.QUALIFIED);
        open.setQualified(true);

        var funnel = funnelOf(open).funnel(null, null, LeadFunnelService.Dimension.SOURCE);

        assertThat(gap(funnel, "qualified").lost()).isZero();
        assertThat(gap(funnel, "qualified").open()).isEqualTo(1);
        assertThat(gap(funnel, "qualified").lostValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void neverHoldsMoreLeadsOnAStepThanTheStepAboveIt() {
        var booked = lead("500000", StageRole.WON);
        booked.setQualified(true);
        booked.setMet(true);
        booked.setBooked(true);
        var unanswered = lead("250000", StageRole.NEW);
        unanswered.setFirstResponseAt(null);

        var funnel = funnelOf(booked, unanswered).funnel(null, null, LeadFunnelService.Dimension.SOURCE);

        assertThat(funnel.rungs()).extracting(LeadFunnelService.Rung::leads)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(reached(funnel, "inquiry")).isEqualTo(2);
        assertThat(reached(funnel, "answered")).isEqualTo(1);
        assertThat(reached(funnel, "booked")).isEqualTo(1);
    }

    @Test
    void valuesEachStepAtTheWeddingBudgetsOnItAndTheFeeThoseEarn() {
        var booked = lead("500000", StageRole.WON);
        booked.setQualified(true);
        booked.setMet(true);
        booked.setBooked(true);
        var silent = lead("0", StageRole.NEW);
        silent.setBudget(null);

        var funnel = funnelOf(booked, silent).funnel(null, null, LeadFunnelService.Dimension.SOURCE);
        var top = funnel.rungs().get(0);

        // The inquiry that never stated a budget counts as a lead and as no money, not as absent.
        assertThat(top.leads()).isEqualTo(2);
        assertThat(top.value()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(top.fee()).isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    void windowsOnTheDateTheInquiryArrived() {
        var inside = lead("400000", StageRole.QUALIFIED);
        var outside = lead("400000", StageRole.QUALIFIED);
        outside.setDate(LocalDateTime.of(2025, 1, 5, 9, 0));

        var funnel = funnelOf(inside, outside).funnel(LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 12, 31, 0, 0), LeadFunnelService.Dimension.SOURCE);

        assertThat(funnel.leads()).isEqualTo(1);
    }

    @Test
    void splitsTheSameLadderByTheChosenDimension() {
        var meta = lead("500000", StageRole.WON);
        meta.setQualified(true);
        meta.setMet(true);
        meta.setBooked(true);
        var press = lead("300000", StageRole.QUALIFYING);
        press.setSource(MarketingSource.PRESS);

        var funnel = funnelOf(meta, press).funnel(null, null, LeadFunnelService.Dimension.SOURCE);

        assertThat(funnel.dimensionLabel()).isEqualTo("First touch");
        assertThat(funnel.segments()).extracting(LeadFunnelService.Segment::label)
                .containsExactlyInAnyOrder("Meta Ads", "Press");
        var metaSegment = funnel.segments().stream()
                .filter(segment -> segment.label().equals("Meta Ads")).findFirst().orElseThrow();
        assertThat(metaSegment.booked()).isEqualTo(1);
        assertThat(metaSegment.bookedValue()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(metaSegment.bookedFee()).isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    void narrowsTheLadderToOneSliceWhileTheBreakdownStillCoversThePeriod() {
        var meta = lead("500000", StageRole.WON);
        meta.setQualified(true);
        meta.setMet(true);
        meta.setBooked(true);
        var press = lead("300000", StageRole.QUALIFYING);
        press.setSource(MarketingSource.PRESS);

        var funnel = funnelOf(meta, press)
                .funnel(null, null, LeadFunnelService.Dimension.SOURCE, "Press");

        assertThat(funnel.segment()).isEqualTo("Press");
        assertThat(funnel.leads()).isEqualTo(1);
        assertThat(reached(funnel, "inquiry")).isEqualTo(1);
        assertThat(reached(funnel, "booked")).isZero();
        // The rows to pick another channel from are still every channel of the period.
        assertThat(funnel.segments()).extracting(LeadFunnelService.Segment::label)
                .containsExactlyInAnyOrder("Meta Ads", "Press");
    }

    @Test
    void readsASliceKeyThisPeriodDoesNotHaveAsNoSlice() {
        var meta = lead("500000", StageRole.WON);
        meta.setBooked(true);

        var funnel = funnelOf(meta).funnel(null, null, LeadFunnelService.Dimension.SOURCE, "Google Ads");

        // Not "every lead": a stale key would otherwise pass the whole business off as one channel.
        assertThat(funnel.segment()).isNull();
        assertThat(funnel.leads()).isEqualTo(1);
    }

    @Test
    void reportsBookedFeePerEuroSpentAndLeavesItBlankWhereNothingWasSpent() {
        var meta = lead("500000", StageRole.WON);
        meta.setQualified(true);
        meta.setMet(true);
        meta.setBooked(true);
        meta.setAcquisitionCost(new BigDecimal("12000"));
        var referral = lead("400000", StageRole.WON);
        referral.setSource(MarketingSource.REFERRAL);
        referral.setQualified(true);
        referral.setMet(true);
        referral.setBooked(true);
        referral.setAcquisitionCost(BigDecimal.ZERO);

        var funnel = funnelOf(meta, referral).funnel(null, null, LeadFunnelService.Dimension.SOURCE);
        var paid = funnel.segments().stream()
                .filter(segment -> segment.label().equals("Meta Ads")).findFirst().orElseThrow();
        var unpaid = funnel.segments().stream()
                .filter(segment -> segment.label().equals("Referral")).findFirst().orElseThrow();

        // €60k of planning fee on €12k of spend.
        assertThat(paid.cost()).isEqualByComparingTo(new BigDecimal("12000"));
        assertThat(paid.returnOnSpend()).isEqualByComparingTo(new BigDecimal("5.00"));
        // A referral did not earn an infinite return; the ratio does not apply to it.
        assertThat(unpaid.returnOnSpend()).isNull();
    }
}
