package com.weddingplanner.crm.domain;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Document;
import su.onno.annotations.TabularSection;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.model.DocumentObject;
import su.onno.types.Ref;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One couple's interest in a wedding, with the marketing attribution it arrived on and the contact
 * history that followed. Everything the marketing pages report is derived here in {@link
 * #beforeWrite()} rather than computed per chart, so a number means the same thing on every surface.
 *
 * <p>Attribution follows the usual two-model split: {@link #source} is the <b>first touch</b> (the
 * property that created the demand) and {@link #lastTouchSource} is the <b>last touch</b> before the
 * inquiry (the one that closed the click). Reporting on only one of them systematically flatters
 * whichever half of the funnel it sits in.</p>
 *
 * <p>The rate fields ({@link #getQualifiedRate()} and friends) hold 100 or 0 rather than a boolean,
 * so a dashboard {@code avg} over them is the conversion percentage for whatever slice it groups by.
 * The framework's aggregates are count/sum/avg/min/max — there is no ratio metric — and encoding the
 * indicator numerically is what makes "booking rate by source" a first-class server-side query.</p>
 */
@Document(name = "LeadInquiries", title = "Lead inquiries", numberPrefix = "LEAD-", context = "Sales")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"MANAGER", "ADMIN"})
public class LeadInquiry extends DocumentObject implements BeforeWriteHandler {

    @Attribute(displayName = "Contact")
    private su.onno.types.Ref<Contact> contact;

    public su.onno.types.Ref<Contact> getContact() { return contact; }
    public void setContact(su.onno.types.Ref<Contact> contact) { this.contact = contact; }

    private static final BigDecimal TARGET_THRESHOLD = new BigDecimal("300000");
    private static final BigDecimal VIP_THRESHOLD = new BigDecimal("500000");
    private static final BigDecimal UPPER_VIP_THRESHOLD = new BigDecimal("750000");

    /** Rate columns are percentages, so an {@code avg} over a slice reads straight as one. */
    private static final BigDecimal YES = new BigDecimal("100.00");
    private static final BigDecimal NO = new BigDecimal("0.00");

    /**
     * Wedding Planner's planning fee as a share of the couple's wedding budget — <b>an assumption, not a
     * measurement</b>, and one to confirm with the client before any of these numbers are quoted.
     *
     * <p>It exists because marketing arithmetic needs a revenue figure that is Wedding Planner's own. Setting a
     * channel's cost against the couple's whole wedding budget would put a €250 lead next to a €450k
     * number, and every cost, margin and payback figure on the marketing pages would round away to
     * nothing. The wedding budget stays on the record as {@link #getBookedValue()} for operations;
     * marketing reports on the fee.</p>
     */
    private static final BigDecimal PLANNING_FEE_RATE = new BigDecimal("0.12");

    @Attribute(displayName = "Couple", required = true, length = 200)
    private String coupleName;

    @Attribute(displayName = "Email", email = true, length = 200)
    private String email;

    @Attribute(displayName = "Phone", length = 80)
    private String phone;

    @Attribute(displayName = "Channel", required = true)
    private LeadChannel channel = LeadChannel.WEBSITE_FORM;

    // ----- attribution -------------------------------------------------------------------------

    @Attribute(displayName = "Marketing source (first touch)", required = true)
    private MarketingSource source = MarketingSource.DIRECT;

    @Attribute(displayName = "Marketing source (last touch)")
    private MarketingSource lastTouchSource;

    @Attribute(displayName = "utm_source", length = 120)
    private String utmSource;

    @Attribute(displayName = "utm_medium")
    private UtmMedium utmMedium = UtmMedium.NONE;

    @Attribute(displayName = "utm_campaign", length = 200)
    private String campaign;

    @Attribute(displayName = "utm_content", length = 200)
    private String utmContent;

    @Attribute(displayName = "utm_term", length = 200)
    private String utmTerm;

    @Attribute(displayName = "Landing page", length = 300)
    private String landingPage;

    @Attribute(displayName = "Referrer", length = 200)
    private String referrer;

    // ----- the wedding -------------------------------------------------------------------------

    @Attribute(displayName = "Wedding budget", precision = 15, scale = 2)
    private BigDecimal budget;

    @Attribute(displayName = "Budget band")
    private BudgetBand budgetBand = BudgetBand.UNKNOWN;

    @Attribute(displayName = "Wedding date")
    private LocalDate weddingDate;

    @Attribute(displayName = "Preferred location", length = 200)
    private String preferredLocation;

    @Attribute(displayName = "Guests")
    private Integer guestCount;

    // ----- qualification and routing -----------------------------------------------------------

    @Attribute(displayName = "Qualification")
    private Qualification qualification = Qualification.NEEDS_INFORMATION;

    /** The stage the team filed this couple into; the list itself is theirs to edit. */
    @Attribute(displayName = "Pipeline stage")
    private Ref<PipelineStage> stage;

    /**
     * What that stage means to the code, derived from it on every write. A renamed stage changes
     * this not at all, and reports can group by it without joining the catalog.
     */
    @Attribute(displayName = "Stage means")
    private StageRole stageRole = StageRole.NEW;

    @Attribute(displayName = "Owner")
    private LeadOwner owner = LeadOwner.MANAGER;

    @Attribute(displayName = "Lost reason")
    private LostReason lostReason;

    @Attribute(displayName = "Qualified")
    private boolean qualified;

    @Attribute(displayName = "VIP")
    private boolean vip;

    @Attribute(displayName = "Booked")
    private boolean booked;

    @Attribute(displayName = "Lost")
    private boolean lost;

    @Attribute(displayName = "Met")
    private boolean met;

    @Attribute(displayName = "Active")
    private boolean active = true;

    // ----- activity ----------------------------------------------------------------------------

    @Attribute(displayName = "First response at")
    private LocalDateTime firstResponseAt;

    @Attribute(displayName = "Meeting at")
    private LocalDateTime meetingAt;

    @Attribute(displayName = "Booked at")
    private LocalDateTime bookedAt;

    @Attribute(displayName = "Original inquiry", length = 4000)
    private String originalMessage;

    @Attribute(displayName = "AI summary", length = 2000)
    private String aiSummary;

    // ----- derived reporting columns -------------------------------------------------------------

    @Attribute(displayName = "Speed to lead (minutes)")
    private Integer responseMinutes;

    @Attribute(displayName = "Speed to lead")
    private ResponseBand responseBand = ResponseBand.NO_REPLY;

    @Attribute(displayName = "Days to booking")
    private Integer daysToBooking;

    @Attribute(displayName = "Consideration (days before inquiry)")
    private Integer considerationDays;

    @Attribute(displayName = "Touches")
    private Integer touchCount = 0;

    @Attribute(displayName = "Inbound touches")
    private Integer inboundTouches = 0;

    @Attribute(displayName = "Outbound touches")
    private Integer outboundTouches = 0;

    @Attribute(displayName = "Meetings and visits")
    private Integer meetingTouches = 0;

    @Attribute(displayName = "First touch at")
    private LocalDateTime firstTouchAt;

    @Attribute(displayName = "Last touch at")
    private LocalDateTime lastTouchAt;

    @Attribute(displayName = "Pipeline value", precision = 15, scale = 2)
    private BigDecimal pipelineValue = BigDecimal.ZERO;

    @Attribute(displayName = "Booked wedding value", precision = 15, scale = 2)
    private BigDecimal bookedValue = BigDecimal.ZERO;

    @Attribute(displayName = "Planning fee (pipeline)", precision = 15, scale = 2)
    private BigDecimal pipelineFee = BigDecimal.ZERO;

    @Attribute(displayName = "Planning fee (booked)", precision = 15, scale = 2)
    private BigDecimal bookedFee = BigDecimal.ZERO;

    /**
     * The share of its channel's spend that this lead cost, pushed down from the channel row by
     * {@code MarketingRollupService}. Carrying cost on the lead is what makes blended cost figures
     * exact: {@code avg(acquisitionCost)} over any slice the dashboard can express — a source, a
     * campaign, a budget band, a month — is that slice's real cost per lead, where averaging the
     * channel rows' own CPL would silently weight a month with two leads like a month with forty.
     */
    @Attribute(displayName = "Acquisition cost", precision = 15, scale = 2)
    private BigDecimal acquisitionCost = BigDecimal.ZERO;

    @Attribute(displayName = "Net fee (booked fee − cost)", precision = 15, scale = 2)
    private BigDecimal netValue = BigDecimal.ZERO;

    @Attribute(displayName = "Qualification rate %", precision = 5, scale = 2)
    private BigDecimal qualifiedRate = NO;

    @Attribute(displayName = "Meeting rate %", precision = 5, scale = 2)
    private BigDecimal meetingRate = NO;

    @Attribute(displayName = "Booking rate %", precision = 5, scale = 2)
    private BigDecimal bookingRate = NO;

    @TabularSection(name = "touches")
    private List<LeadTouchpoint> touches = new ArrayList<>();

    public List<LeadTouchpoint> getTouches() { return touches; }
    public void setTouches(List<LeadTouchpoint> touches) { this.touches = touches; }

    @Override
    public void beforeWrite() {
        // The stage list is editable, so these rules ask what a stage means and where it sits in the
        // pipeline, never what it is called. A stage the team inserted of their own reads as an
        // ordinary open stage, and one they renamed reads as whatever role they left on it.
        readStage();
        budgetBand = classifyBudget(budget);
        boolean complete = budget != null && weddingDate != null
                && preferredLocation != null && !preferredLocation.isBlank();

        if (!complete) {
            qualification = Qualification.NEEDS_INFORMATION;
            qualified = false;
            owner = LeadOwner.MANAGER;
            advanceTo(StageRole.QUALIFYING);
        } else if (budget.compareTo(VIP_THRESHOLD) >= 0) {
            qualification = Qualification.VIP;
            qualified = true;
            owner = LeadOwner.FOUNDER;
            advanceTo(StageRole.QUALIFIED);
        } else if (budget.compareTo(TARGET_THRESHOLD) >= 0) {
            qualification = Qualification.TARGET;
            qualified = true;
            owner = LeadOwner.MANAGER;
            advanceTo(StageRole.QUALIFIED);
        } else {
            qualification = Qualification.NOT_A_FIT;
            qualified = false;
            owner = LeadOwner.MANAGER;
        }

        active = stageRole != StageRole.WON && !stageRole.lost();
        vip = qualification == Qualification.VIP;
        booked = stageRole == StageRole.WON;
        lost = stageRole.lost();
        // Lost is a terminal stage, not an outcome of any one field, so the reason only survives
        // while the lead is actually lost — otherwise a reopened lead keeps reporting as churn.
        if (!lost) {
            lostReason = null;
        }
        // Anything at or past the meeting stage has met, including stages the team added after it.
        // Lost sits late in the list without saying anything about whether a meeting happened.
        met = meetingAt != null || (!lost && reachedMeetingStage());
        if (booked && bookedAt == null) {
            bookedAt = LocalDateTime.now();
        }
        if (!booked) {
            bookedAt = null;
        }

        deriveAttribution();
        deriveVelocity();
        deriveValue();
    }

    /** Read the filed stage back: an unfiled lead starts at the first stage of the pipeline. */
    private void readStage() {
        PipelineStageLookup pipeline = PipelineStageLookup.Holder.current();
        if (pipeline == null) return;
        if (stage == null) {
            pipeline.byRole(StageRole.NEW).ifPresent(first -> stage = first);
        }
        stageRole = stage == null ? StageRole.NEW : pipeline.roleOf(stage);
    }

    /** Move a lead forward to the stage playing this role, never backwards and never out of one. */
    private void advanceTo(StageRole role) {
        PipelineStageLookup pipeline = PipelineStageLookup.Holder.current();
        if (pipeline == null || stagePosition() >= rolePosition(role)) return;
        pipeline.byRole(role).ifPresent(target -> {
            stage = target;
            stageRole = role;
        });
    }

    /** Where the lead currently sits; an unresolvable stage sorts last and so never advances. */
    private int stagePosition() {
        PipelineStageLookup pipeline = PipelineStageLookup.Holder.current();
        return pipeline == null ? Integer.MAX_VALUE : pipeline.positionOf(stage);
    }

    /** Whether the lead is filed at or past the meeting stage. Unknown either way counts as no. */
    private boolean reachedMeetingStage() {
        int meeting = rolePosition(StageRole.MEETING);
        int current = stagePosition();
        return meeting != Integer.MAX_VALUE && current != Integer.MAX_VALUE && current >= meeting;
    }

    private int rolePosition(StageRole role) {
        PipelineStageLookup pipeline = PipelineStageLookup.Holder.current();
        return pipeline == null ? Integer.MAX_VALUE
                : pipeline.byRole(role).map(pipeline::positionOf).orElse(Integer.MAX_VALUE);
    }

    /**
     * Roll the touch history up onto the header. The tabular rows are the record of what happened;
     * charts can only group by header columns, so the counts a report needs live here too.
     */
    private void deriveAttribution() {
        if (touches == null) {
            touches = new ArrayList<>();
        }
        List<LeadTouchpoint> ordered = touches.stream()
                .filter(touch -> touch.getAt() != null)
                .sorted(Comparator.comparing(LeadTouchpoint::getAt))
                .toList();

        touchCount = touches.size();
        inboundTouches = (int) touches.stream().filter(t -> isInbound(t.getType())).count();
        outboundTouches = (int) touches.stream().filter(t -> isOutbound(t.getType())).count();
        meetingTouches = (int) touches.stream().filter(t -> isMeeting(t.getType())).count();

        firstTouchAt = ordered.isEmpty() ? getDate() : ordered.get(0).getAt();
        lastTouchAt = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1).getAt();

        // Last touch is the most recent touch that actually carried a marketing source — a reply of
        // ours is contact, not attribution, and crediting it would make every lead self-sourced.
        // Where the history says something, it wins: the rows are the record, not the header.
        MarketingSource fromHistory = ordered.stream()
                .filter(touch -> touch.getSource() != null)
                .reduce((first, second) -> second)
                .map(LeadTouchpoint::getSource)
                .orElse(null);
        if (fromHistory != null) {
            lastTouchSource = fromHistory;
        } else if (lastTouchSource == null) {
            lastTouchSource = source;
        }

        considerationDays = (firstTouchAt == null || getDate() == null || firstTouchAt.isAfter(getDate()))
                ? 0
                : (int) Duration.between(firstTouchAt, getDate()).toDays();
    }

    private void deriveVelocity() {
        if (getDate() != null && firstResponseAt != null && !firstResponseAt.isBefore(getDate())) {
            long minutes = Duration.between(getDate(), firstResponseAt).toMinutes();
            responseMinutes = (int) Math.min(minutes, Integer.MAX_VALUE);
            responseBand = classifyResponse(responseMinutes);
        } else {
            responseMinutes = null;
            responseBand = ResponseBand.NO_REPLY;
        }

        daysToBooking = (getDate() != null && bookedAt != null && !bookedAt.isBefore(getDate()))
                ? (int) Duration.between(getDate(), bookedAt).toDays()
                : null;
    }

    private void deriveValue() {
        BigDecimal amount = budget == null ? BigDecimal.ZERO : budget;
        pipelineValue = (qualified && active) ? amount : BigDecimal.ZERO;
        bookedValue = booked ? amount : BigDecimal.ZERO;
        pipelineFee = fee(pipelineValue);
        bookedFee = fee(bookedValue);
        if (acquisitionCost == null) {
            acquisitionCost = BigDecimal.ZERO;
        }
        netValue = bookedFee.subtract(acquisitionCost);
        qualifiedRate = qualified ? YES : NO;
        meetingRate = met ? YES : NO;
        bookingRate = booked ? YES : NO;
    }

    private static BigDecimal fee(BigDecimal weddingValue) {
        return weddingValue.multiply(PLANNING_FEE_RATE).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Wedding Planner's planning fee on a wedding budget, for readers that total budgets of their own — the
     * funnel's steps, for one. It lives here rather than in the caller so there is exactly one place
     * the fee assumption is written down.
     */
    public static BigDecimal planningFee(BigDecimal weddingValue) {
        return fee(weddingValue == null ? BigDecimal.ZERO : weddingValue);
    }

    private static boolean isInbound(TouchType type) {
        return type == TouchType.INBOUND_MESSAGE || type == TouchType.FORM_SUBMIT
                || type == TouchType.AD_CLICK || type == TouchType.SITE_VISIT;
    }

    private static boolean isOutbound(TouchType type) {
        return type == TouchType.OUTBOUND_REPLY || type == TouchType.PROPOSAL_SENT;
    }

    private static boolean isMeeting(TouchType type) {
        return type == TouchType.CALL || type == TouchType.VIDEO_MEETING || type == TouchType.VENUE_VISIT;
    }

    private static ResponseBand classifyResponse(int minutes) {
        if (minutes < 5) return ResponseBand.UNDER_5_MIN;
        if (minutes < 60) return ResponseBand.UNDER_1_HOUR;
        if (minutes < 60 * 24) return ResponseBand.UNDER_24_HOURS;
        return ResponseBand.OVER_24_HOURS;
    }

    public static BudgetBand classifyBudget(BigDecimal value) {
        if (value == null) return BudgetBand.UNKNOWN;
        if (value.compareTo(TARGET_THRESHOLD) < 0) return BudgetBand.BELOW_300K;
        if (value.compareTo(VIP_THRESHOLD) < 0) return BudgetBand.FROM_300K_TO_499K;
        if (value.compareTo(UPPER_VIP_THRESHOLD) < 0) return BudgetBand.FROM_500K_TO_749K;
        return BudgetBand.FROM_750K;
    }

    public String getCoupleName() { return coupleName; }
    public void setCoupleName(String coupleName) { this.coupleName = coupleName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public LeadChannel getChannel() { return channel; }
    public void setChannel(LeadChannel channel) { this.channel = channel; }
    public MarketingSource getSource() { return source; }
    public void setSource(MarketingSource source) { this.source = source; }
    public MarketingSource getLastTouchSource() { return lastTouchSource; }
    public void setLastTouchSource(MarketingSource lastTouchSource) { this.lastTouchSource = lastTouchSource; }
    public String getUtmSource() { return utmSource; }
    public void setUtmSource(String utmSource) { this.utmSource = utmSource; }
    public UtmMedium getUtmMedium() { return utmMedium; }
    public void setUtmMedium(UtmMedium utmMedium) { this.utmMedium = utmMedium; }
    public String getCampaign() { return campaign; }
    public void setCampaign(String campaign) { this.campaign = campaign; }
    public String getUtmContent() { return utmContent; }
    public void setUtmContent(String utmContent) { this.utmContent = utmContent; }
    public String getUtmTerm() { return utmTerm; }
    public void setUtmTerm(String utmTerm) { this.utmTerm = utmTerm; }
    public String getLandingPage() { return landingPage; }
    public void setLandingPage(String landingPage) { this.landingPage = landingPage; }
    public String getReferrer() { return referrer; }
    public void setReferrer(String referrer) { this.referrer = referrer; }
    public BigDecimal getBudget() { return budget; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public BudgetBand getBudgetBand() { return budgetBand; }
    public void setBudgetBand(BudgetBand budgetBand) { this.budgetBand = budgetBand; }
    public LocalDate getWeddingDate() { return weddingDate; }
    public void setWeddingDate(LocalDate weddingDate) { this.weddingDate = weddingDate; }
    public String getPreferredLocation() { return preferredLocation; }
    public void setPreferredLocation(String preferredLocation) { this.preferredLocation = preferredLocation; }
    public Integer getGuestCount() { return guestCount; }
    public void setGuestCount(Integer guestCount) { this.guestCount = guestCount; }
    public Qualification getQualification() { return qualification; }
    public void setQualification(Qualification qualification) { this.qualification = qualification; }
    public Ref<PipelineStage> getStage() { return stage; }
    public void setStage(Ref<PipelineStage> stage) { this.stage = stage; }
    public StageRole getStageRole() { return stageRole; }
    public void setStageRole(StageRole stageRole) { this.stageRole = stageRole; }
    public LeadOwner getOwner() { return owner; }
    public void setOwner(LeadOwner owner) { this.owner = owner; }
    public LostReason getLostReason() { return lostReason; }
    public void setLostReason(LostReason lostReason) { this.lostReason = lostReason; }
    public boolean isQualified() { return qualified; }
    public void setQualified(boolean qualified) { this.qualified = qualified; }
    public boolean isVip() { return vip; }
    public void setVip(boolean vip) { this.vip = vip; }
    public boolean isBooked() { return booked; }
    public void setBooked(boolean booked) { this.booked = booked; }
    public boolean isLost() { return lost; }
    public void setLost(boolean lost) { this.lost = lost; }
    public boolean isMet() { return met; }
    public void setMet(boolean met) { this.met = met; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getFirstResponseAt() { return firstResponseAt; }
    public void setFirstResponseAt(LocalDateTime firstResponseAt) { this.firstResponseAt = firstResponseAt; }
    public LocalDateTime getMeetingAt() { return meetingAt; }
    public void setMeetingAt(LocalDateTime meetingAt) { this.meetingAt = meetingAt; }
    public LocalDateTime getBookedAt() { return bookedAt; }
    public void setBookedAt(LocalDateTime bookedAt) { this.bookedAt = bookedAt; }
    public String getOriginalMessage() { return originalMessage; }
    public void setOriginalMessage(String originalMessage) { this.originalMessage = originalMessage; }
    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
    public Integer getResponseMinutes() { return responseMinutes; }
    public void setResponseMinutes(Integer responseMinutes) { this.responseMinutes = responseMinutes; }
    public ResponseBand getResponseBand() { return responseBand; }
    public void setResponseBand(ResponseBand responseBand) { this.responseBand = responseBand; }
    public Integer getDaysToBooking() { return daysToBooking; }
    public void setDaysToBooking(Integer daysToBooking) { this.daysToBooking = daysToBooking; }
    public Integer getConsiderationDays() { return considerationDays; }
    public void setConsiderationDays(Integer considerationDays) { this.considerationDays = considerationDays; }
    public Integer getTouchCount() { return touchCount; }
    public void setTouchCount(Integer touchCount) { this.touchCount = touchCount; }
    public Integer getInboundTouches() { return inboundTouches; }
    public void setInboundTouches(Integer inboundTouches) { this.inboundTouches = inboundTouches; }
    public Integer getOutboundTouches() { return outboundTouches; }
    public void setOutboundTouches(Integer outboundTouches) { this.outboundTouches = outboundTouches; }
    public Integer getMeetingTouches() { return meetingTouches; }
    public void setMeetingTouches(Integer meetingTouches) { this.meetingTouches = meetingTouches; }
    public LocalDateTime getFirstTouchAt() { return firstTouchAt; }
    public void setFirstTouchAt(LocalDateTime firstTouchAt) { this.firstTouchAt = firstTouchAt; }
    public LocalDateTime getLastTouchAt() { return lastTouchAt; }
    public void setLastTouchAt(LocalDateTime lastTouchAt) { this.lastTouchAt = lastTouchAt; }
    public BigDecimal getPipelineValue() { return pipelineValue; }
    public void setPipelineValue(BigDecimal pipelineValue) { this.pipelineValue = pipelineValue; }
    public BigDecimal getBookedValue() { return bookedValue; }
    public void setBookedValue(BigDecimal bookedValue) { this.bookedValue = bookedValue; }
    public BigDecimal getPipelineFee() { return pipelineFee; }
    public void setPipelineFee(BigDecimal pipelineFee) { this.pipelineFee = pipelineFee; }
    public BigDecimal getBookedFee() { return bookedFee; }
    public void setBookedFee(BigDecimal bookedFee) { this.bookedFee = bookedFee; }
    public BigDecimal getAcquisitionCost() { return acquisitionCost; }
    public void setAcquisitionCost(BigDecimal acquisitionCost) { this.acquisitionCost = acquisitionCost; }
    public BigDecimal getNetValue() { return netValue; }
    public void setNetValue(BigDecimal netValue) { this.netValue = netValue; }
    public BigDecimal getQualifiedRate() { return qualifiedRate; }
    public void setQualifiedRate(BigDecimal qualifiedRate) { this.qualifiedRate = qualifiedRate; }
    public BigDecimal getMeetingRate() { return meetingRate; }
    public void setMeetingRate(BigDecimal meetingRate) { this.meetingRate = meetingRate; }
    public BigDecimal getBookingRate() { return bookingRate; }
    public void setBookingRate(BigDecimal bookingRate) { this.bookingRate = bookingRate; }
}
