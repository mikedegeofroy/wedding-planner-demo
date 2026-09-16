package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.BudgetBand;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.LeadTouchpoint;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.domain.TouchType;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The acquisition funnel as one computation: how far each lead actually got, what fell out between
 * one step and the next, and what the money was worth at every point.
 *
 * <p>The charts on the marketing pages each answer one slice of this — a count here, a rate there —
 * and a reader has to hold four of them in their head to see where the business is losing couples.
 * This produces the whole ladder at once, with the drop between rungs as a first-class figure
 * rather than a subtraction the reader performs.</p>
 *
 * <h2>Why a lead's step is the <em>furthest</em> one it reached</h2>
 *
 * <p>A stage says where a couple sits <b>today</b>; a funnel needs where they <b>got to</b>. Lost is
 * a terminal stage, so a couple who met, received a proposal and then went quiet reads as "lost" on
 * the stage field and would vanish from every earlier rung. So each lead is placed at the furthest
 * step any evidence supports — the pipeline stage it is filed in, the flags {@code beforeWrite}
 * derives, and its own touch history — and the rungs below it are implied. That makes the funnel
 * monotone by construction: a step can never hold more leads than the step above it.</p>
 *
 * <h2>A drop is not automatically a loss</h2>
 *
 * <p>Between two steps, the leads that did not advance are split into <b>lost</b> (a terminal stage,
 * with the reason the team recorded) and <b>still open</b> (in play, simply not there yet). Reporting
 * them together would read a healthy young pipeline as churn, which is the usual way a funnel chart
 * lies about a recent period.</p>
 *
 * <h2>What a step is worth</h2>
 *
 * <p>Two figures, because they answer different questions: the couples' <b>wedding budgets</b> (what
 * is in play) and Wedding Planner's <b>planning fee</b> on them (what Wedding Planner earns from it). Inquiries that never
 * stated a budget count as zero value and still count as leads, so a step's average is over every
 * lead on it, silent ones included — the honest reading for "what is a lead at this step worth".</p>
 */
@Service
public class LeadFunnelService {

    /** The rungs, top to bottom. Order is the funnel; each key is what the widget draws a band for. */
    public enum Step {
        INQUIRY("Inquiries", "Every inquiry that arrived in the period."),
        ANSWERED("Answered", "A human reply went out. The one step the team controls outright."),
        QUALIFIED("Qualified", "Budget, date and location on file, at or above the €300k target."),
        MET("Met", "Reached a call, a video meeting or a venue visit."),
        PROPOSAL("Proposal sent", "A proposal or contract went out."),
        BOOKED("Booked", "Signed. The planning fee on these is the revenue the funnel produced.");

        private final String label;
        private final String hint;

        Step(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    /** The dimension a funnel can be split by, so "where do we leak" can be asked per channel. */
    public enum Dimension {
        SOURCE("First touch", lead -> LeadFacts.label(lead.getSource()), lead -> LeadFacts.color(lead.getSource())),
        LAST_TOUCH("Last touch", lead -> LeadFacts.label(lead.getLastTouchSource()),
                lead -> LeadFacts.color(lead.getLastTouchSource())),
        CHANNEL("Channel", lead -> LeadFacts.label(lead.getChannel()), lead -> LeadFacts.color(lead.getChannel())),
        BAND("Budget band", lead -> LeadFacts.label(band(lead)), lead -> LeadFacts.color(band(lead))),
        OWNER("Owner", lead -> LeadFacts.label(lead.getOwner()), lead -> LeadFacts.color(lead.getOwner()));

        private final String label;
        private final Function<LeadInquiry, String> name;
        private final Function<LeadInquiry, String> color;

        Dimension(String label, Function<LeadInquiry, String> name, Function<LeadInquiry, String> color) {
            this.label = label;
            this.name = name;
            this.color = color;
        }

        public String label() {
            return label;
        }

        /** The dimension for a request parameter; an unknown or missing one reads as first touch. */
        public static Dimension of(String key) {
            if (key == null || key.isBlank()) return SOURCE;
            for (Dimension value : values()) {
                if (value.name().equalsIgnoreCase(key.replace('-', '_'))) return value;
            }
            return SOURCE;
        }

        private static BudgetBand band(LeadInquiry lead) {
            return lead.getBudgetBand() == null ? BudgetBand.UNKNOWN : lead.getBudgetBand();
        }
    }

    /**
     * One rung.
     *
     * @param leads how many of the period's leads reached this step or went past it
     * @param value the couples' wedding budgets at this step
     * @param fee   Wedding Planner's planning fee on that value
     */
    public record Rung(String key, String label, String hint, long leads, BigDecimal value, BigDecimal fee) {}

    /** One recorded reason a lead was lost at a gap, with the money that left with it. */
    public record Reason(String key, String label, String color, long leads, BigDecimal value, BigDecimal fee) {}

    /**
     * What happened between two rungs.
     *
     * @param lost      leads that ended here — a terminal stage, with a reason where the team left one
     * @param open      leads still in play that have simply not reached the next step yet
     * @param lostValue wedding budgets that left with the lost leads
     * @param lostFee   the planning fee that left with them — the cost of this gap to Wedding Planner
     */
    public record Gap(String fromKey, String toKey, String label, long lost, long open,
            BigDecimal lostValue, BigDecimal lostFee, List<Reason> reasons) {}

    /**
     * One slice of the split dimension: its own ladder, so channels can be compared rung by rung.
     *
     * @param cost   the acquisition cost carried by this slice's leads — its share of channel spend
     * @param returnOnSpend booked fee per euro of that cost, or {@code null} where nothing was spent:
     *                      organic and referral do not earn an infinite return, the figure does not
     *                      apply to them (the same reading {@code MarketingPerformance} takes)
     */
    public record Segment(String key, String label, String color, long leads, long booked,
            BigDecimal bookedValue, BigDecimal bookedFee, BigDecimal cost, BigDecimal returnOnSpend,
            List<Long> reached) {}

    /**
     * The whole board.
     *
     * @param rungs    the ladder, top to bottom — of the selected slice when there is one
     * @param gaps     one entry per pair of adjacent rungs
     * @param segments the same ladder split by {@code dimension}, biggest first. Always the whole
     *                 period, even while one slice is selected, so the reader can see what they are
     *                 looking at against the rest and pick another without clearing the first
     * @param segment  the slice the ladder is narrowed to, or {@code null} for every lead
     */
    public record Funnel(String from, String to, long leads, String dimension, String dimensionLabel,
            String segment, List<Rung> rungs, List<Gap> gaps, List<Segment> segments) {}

    /** How many slices the split is worth reading; the rest are folded into one "Other" row. */
    private static final int SEGMENT_LIMIT = 7;

    /** The folded tail's key — not a value any lead carries, so selecting it means "all the rest". */
    private static final String OTHER = "other";

    private final LeadInquiryRepository leads;
    private final PipelineStageService stages;

    public LeadFunnelService(LeadInquiryRepository leads, PipelineStageService stages) {
        this.leads = leads;
        this.stages = stages;
    }

    /**
     * The funnel for inquiries that arrived inside {@code [from, to)}. Both bounds are optional: the
     * dashboard's "all time" sends neither.
     */
    public Funnel funnel(LocalDateTime from, LocalDateTime to, Dimension dimension) {
        return funnel(from, to, dimension, null);
    }

    /**
     * The same funnel narrowed to one slice of {@code dimension} — the ladder for Meta Ads alone,
     * say. Comparing shapes in the breakdown says <em>which</em> channel leaks; drawing that
     * channel's own ladder says <em>where</em>, with the same drop-off, loss reasons and money the
     * whole-period funnel carries. An unknown slice key narrows to nothing rather than silently
     * reading as every lead, so a stale key cannot pass off the whole business as one channel.
     */
    public Funnel funnel(LocalDateTime from, LocalDateTime to, Dimension dimension, String segmentKey) {
        List<LeadInquiry> window = leads.findAllActive().stream()
                .filter(lead -> lead.getDate() != null)
                .filter(lead -> from == null || !lead.getDate().isBefore(from))
                .filter(lead -> to == null || lead.getDate().isBefore(to))
                .toList();

        Step[] ladder = Step.values();
        List<Segment> segments = segments(window, dimension, ladder);
        String selected = selection(segmentKey, segments);
        List<LeadInquiry> slice = selected == null ? window : narrow(window, dimension, selected, segments);

        // Each lead is counted once, at the furthest rung it reached; every rung below is implied.
        Map<Step, List<LeadInquiry>> endedAt = new LinkedHashMap<>();
        for (Step step : ladder) endedAt.put(step, new ArrayList<>());
        for (LeadInquiry lead : slice) endedAt.get(furthest(lead)).add(lead);

        List<Rung> rungs = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        for (int index = 0; index < ladder.length; index++) {
            List<LeadInquiry> reached = new ArrayList<>();
            for (int deeper = index; deeper < ladder.length; deeper++) reached.addAll(endedAt.get(ladder[deeper]));
            BigDecimal value = total(reached);
            rungs.add(new Rung(ladder[index].name().toLowerCase(), ladder[index].label, ladder[index].hint,
                    reached.size(), value, LeadInquiry.planningFee(value)));

            if (index + 1 < ladder.length) {
                gaps.add(gap(ladder[index], ladder[index + 1], endedAt.get(ladder[index])));
            }
        }

        return new Funnel(text(from), text(to), slice.size(), dimension.name().toLowerCase(),
                dimension.label(), selected, rungs, gaps, segments);
    }

    /** The slice actually on offer for this dimension and period; anything else reads as no slice. */
    private static String selection(String key, List<Segment> segments) {
        if (key == null || key.isBlank()) return null;
        return segments.stream().map(Segment::key).filter(key::equals).findFirst().orElse(null);
    }

    /**
     * The leads behind one breakdown row. {@code other} is the folded tail rather than a value any
     * lead carries, so it is read as "everything the named rows left over" — the two always agree.
     */
    private static List<LeadInquiry> narrow(List<LeadInquiry> window, Dimension dimension, String key,
            List<Segment> segments) {
        if (OTHER.equals(key)) {
            List<String> named = segments.stream().map(Segment::key).filter(value -> !OTHER.equals(value)).toList();
            return window.stream().filter(lead -> !named.contains(name(dimension, lead))).toList();
        }
        return window.stream().filter(lead -> key.equals(name(dimension, lead))).toList();
    }

    /** A lead's value on the split dimension, with the same fallback the breakdown groups by. */
    private static String name(Dimension dimension, LeadInquiry lead) {
        String value = dimension.name.apply(lead);
        return value == null ? "Unknown" : value;
    }

    /** The leads that stopped at {@code from}, read as loss and as work still in progress. */
    private Gap gap(Step from, Step to, List<LeadInquiry> stopped) {
        List<LeadInquiry> lost = stopped.stream().filter(LeadInquiry::isLost).toList();
        var byReason = new LinkedHashMap<String, List<LeadInquiry>>();
        for (LeadInquiry lead : lost) {
            byReason.computeIfAbsent(LeadFacts.label(lead.getLostReason()) == null
                    ? "Not recorded" : LeadFacts.label(lead.getLostReason()), key -> new ArrayList<>()).add(lead);
        }
        List<Reason> reasons = byReason.entrySet().stream()
                .map(entry -> new Reason(entry.getKey(), entry.getKey(),
                        LeadFacts.color(entry.getValue().get(0).getLostReason()),
                        entry.getValue().size(), total(entry.getValue()),
                        LeadInquiry.planningFee(total(entry.getValue()))))
                .sorted(Comparator.comparingLong(Reason::leads).reversed())
                .toList();
        BigDecimal lostValue = total(lost);
        return new Gap(from.name().toLowerCase(), to.name().toLowerCase(),
                from.label + " → " + to.label, lost.size(), stopped.size() - lost.size(),
                lostValue, LeadInquiry.planningFee(lostValue), reasons);
    }

    /** The same ladder per slice of the dimension, so one channel's leak can be read against another's. */
    private List<Segment> segments(List<LeadInquiry> window, Dimension dimension, Step[] ladder) {
        var grouped = new LinkedHashMap<String, List<LeadInquiry>>();
        for (LeadInquiry lead : window) {
            grouped.computeIfAbsent(name(dimension, lead), key -> new ArrayList<>()).add(lead);
        }
        List<Segment> segments = grouped.entrySet().stream()
                .map(entry -> segment(entry.getKey(), dimension.color.apply(entry.getValue().get(0)),
                        entry.getValue(), ladder))
                .sorted(Comparator.comparingLong(Segment::leads).reversed())
                .toList();
        if (segments.size() <= SEGMENT_LIMIT) return segments;

        // Past a handful of slices the chart stops being readable and the tail stops being actionable,
        // so the tail is summed rather than dropped — the segment counts still add up to the funnel.
        List<Segment> head = new ArrayList<>(segments.subList(0, SEGMENT_LIMIT));
        List<Segment> tail = segments.subList(SEGMENT_LIMIT, segments.size());
        List<Long> reached = new ArrayList<>();
        for (int index = 0; index < ladder.length; index++) {
            long sum = 0;
            for (Segment segment : tail) sum += segment.reached().get(index);
            reached.add(sum);
        }
        BigDecimal tailFee = tail.stream().map(Segment::bookedFee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tailCost = tail.stream().map(Segment::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        head.add(new Segment(OTHER, "Other", null,
                tail.stream().mapToLong(Segment::leads).sum(),
                tail.stream().mapToLong(Segment::booked).sum(),
                tail.stream().map(Segment::bookedValue).reduce(BigDecimal.ZERO, BigDecimal::add),
                tailFee, tailCost, returnOnSpend(tailFee, tailCost), reached));
        return head;
    }

    private Segment segment(String label, String color, List<LeadInquiry> slice, Step[] ladder) {
        List<Long> reached = new ArrayList<>();
        for (Step step : ladder) {
            int rung = step.ordinal();
            reached.add(slice.stream().filter(lead -> furthest(lead).ordinal() >= rung).count());
        }
        List<LeadInquiry> booked = slice.stream().filter(LeadInquiry::isBooked).toList();
        BigDecimal bookedValue = total(booked);
        BigDecimal bookedFee = LeadInquiry.planningFee(bookedValue);
        BigDecimal cost = spend(slice);
        return new Segment(label, label, color, slice.size(), booked.size(), bookedValue,
                bookedFee, cost, returnOnSpend(bookedFee, cost), reached);
    }

    /** What this slice's leads cost to acquire: each lead carries its share of its channel's spend. */
    private static BigDecimal spend(List<LeadInquiry> slice) {
        return slice.stream().map(lead -> lead.getAcquisitionCost() == null
                ? BigDecimal.ZERO : lead.getAcquisitionCost()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Planning fee booked per euro spent acquiring the slice — the fee, not the couples' budgets,
     * because the budgets are the clients' money and returning them is not what the spend bought.
     * Nothing spent means the ratio does not apply, which is not the same as a return of zero.
     */
    private static BigDecimal returnOnSpend(BigDecimal bookedFee, BigDecimal cost) {
        if (cost == null || cost.signum() <= 0) return null;
        return bookedFee.divide(cost, 2, RoundingMode.HALF_UP);
    }

    /**
     * The furthest step a lead reached, read from every record that can evidence it: the stage it is
     * filed in, the flags derived on write, and its touch history. Each test only ever moves a lead
     * further down, so the answer is the deepest one that holds.
     */
    private Step furthest(LeadInquiry lead) {
        Step step = Step.INQUIRY;
        // A reply, not merely outbound activity: a proposal counted as a touch is not evidence that
        // anybody answered the couple's first message, which is what this rung is about.
        if (lead.getFirstResponseAt() != null || hasTouch(lead, TouchType.OUTBOUND_REPLY)) {
            step = Step.ANSWERED;
        }
        if (lead.isQualified() || atLeast(lead, StageRole.QUALIFIED)) step = max(step, Step.QUALIFIED);
        if (lead.isMet() || hasTouch(lead, TouchType.VIDEO_MEETING, TouchType.VENUE_VISIT, TouchType.CALL)) {
            step = max(step, Step.MET);
        }
        // A lost lead keeps whatever it reached: the proposal it received is a record, and dropping it
        // would move every late loss back up the funnel and flatter the steps it actually passed.
        if (hasTouch(lead, TouchType.PROPOSAL_SENT) || atLeast(lead, StageRole.PROPOSAL)) {
            step = max(step, Step.PROPOSAL);
        }
        if (lead.isBooked()) step = Step.BOOKED;
        return step;
    }

    /** Whether the lead sits at or past the stage playing this role. A lost lead is nowhere on it. */
    private boolean atLeast(LeadInquiry lead, StageRole role) {
        if (lead.getStage() == null || lead.isLost()) return false;
        int target = stages.stageFor(role).map(stage -> stage.getPosition() == null
                ? Integer.MAX_VALUE : stage.getPosition()).orElse(Integer.MAX_VALUE);
        int current = stages.positionOf(lead.getStage());
        return target != Integer.MAX_VALUE && current != Integer.MAX_VALUE && current >= target;
    }

    private static boolean hasTouch(LeadInquiry lead, TouchType... types) {
        if (lead.getTouches() == null) return false;
        for (LeadTouchpoint touch : lead.getTouches()) {
            for (TouchType type : types) {
                if (touch.getType() == type) return true;
            }
        }
        return false;
    }

    private static Step max(Step current, Step candidate) {
        return candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    /** Wedding budgets on a set of leads; an inquiry that never stated one counts as zero, not as absent. */
    private static BigDecimal total(List<LeadInquiry> leads) {
        return leads.stream().map(lead -> lead.getBudget() == null ? BigDecimal.ZERO : lead.getBudget())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String text(LocalDateTime moment) {
        return moment == null ? null : moment.toString();
    }
}
