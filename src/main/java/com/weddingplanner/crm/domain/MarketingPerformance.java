package com.weddingplanner.crm.domain;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.lifecycle.BeforeWriteHandler;
import su.onno.model.CatalogObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * One month of one acquisition channel: what was spent, what the platform delivered, and what the
 * pipeline did with it. The spend side (spend, impressions, clicks) is imported from the ad
 * platform; the results side (leads … revenue) is recomputed from {@link LeadInquiry} by
 * {@code MarketingRollupService}, so the cost ratios below can never disagree with the lead list
 * they are supposed to summarise.
 *
 * <p>Rows are keyed by period + source + campaign. Keeping the period on the row is what makes
 * "CPL is rising" answerable — a single all-time row per source can only ever show an average that
 * hides the trend.</p>
 */
@Catalog(name = "MarketingPerformance", title = "Channel performance", codePrefix = "MKT-", context = "Marketing")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"MANAGER", "ADMIN"})
public class MarketingPerformance extends CatalogObject implements BeforeWriteHandler {

    @Attribute(displayName = "Month", required = true)
    private LocalDate period;

    @Attribute(displayName = "Source", required = true)
    private MarketingSource source = MarketingSource.DIRECT;

    @Attribute(displayName = "utm_medium")
    private UtmMedium medium = UtmMedium.NONE;

    @Attribute(displayName = "utm_source", length = 120)
    private String utmSource;

    @Attribute(displayName = "Campaign", length = 200)
    private String campaign;

    // ----- spend side: imported from the ad platform ---------------------------------------------

    @Attribute(displayName = "Spend", precision = 15, scale = 2)
    private BigDecimal spend = BigDecimal.ZERO;

    @Attribute(displayName = "Impressions")
    private Integer impressions = 0;

    @Attribute(displayName = "Clicks")
    private Integer clicks = 0;

    // ----- results side: recomputed from the lead documents --------------------------------------

    @Attribute(displayName = "Leads")
    private Integer leads = 0;

    @Attribute(displayName = "Qualified leads")
    private Integer qualifiedLeads = 0;

    @Attribute(displayName = "VIP leads")
    private Integer vipLeads = 0;

    @Attribute(displayName = "Meetings")
    private Integer meetings = 0;

    @Attribute(displayName = "Bookings")
    private Integer bookings = 0;

    /**
     * Wedding Planner's own revenue — the planning fees of the weddings this cell booked, not the couples'
     * wedding budgets. Every cost ratio below divides into this, because a channel's return has to
     * be measured in money Wedding Planner actually receives. The wedding budgets are carried separately in
     * {@link #getBookedWeddingValue()}.
     */
    @Attribute(displayName = "Fee revenue", precision = 15, scale = 2)
    private BigDecimal revenue = BigDecimal.ZERO;

    @Attribute(displayName = "Booked wedding value", precision = 15, scale = 2)
    private BigDecimal bookedWeddingValue = BigDecimal.ZERO;

    @Attribute(displayName = "Pipeline value", precision = 15, scale = 2)
    private BigDecimal pipeline = BigDecimal.ZERO;

    // ----- derived ratios ------------------------------------------------------------------------

    @Attribute(displayName = "CTR %", precision = 7, scale = 2)
    private BigDecimal clickThroughRate;

    @Attribute(displayName = "CPC", precision = 15, scale = 2)
    private BigDecimal costPerClick;

    @Attribute(displayName = "CPL", precision = 15, scale = 2)
    private BigDecimal costPerLead;

    @Attribute(displayName = "CPQL", precision = 15, scale = 2)
    private BigDecimal costPerQualifiedLead;

    @Attribute(displayName = "Cost per meeting", precision = 15, scale = 2)
    private BigDecimal costPerMeeting;

    @Attribute(displayName = "CAC (cost per booking)", precision = 15, scale = 2)
    private BigDecimal costPerBooking;

    @Attribute(displayName = "Lead → qualified %", precision = 7, scale = 2)
    private BigDecimal qualificationRate;

    @Attribute(displayName = "Qualified → booked %", precision = 7, scale = 2)
    private BigDecimal closeRate;

    @Attribute(displayName = "ROAS", precision = 10, scale = 2)
    private BigDecimal returnOnAdSpend;

    @Attribute(displayName = "Average fee per booking", precision = 15, scale = 2)
    private BigDecimal averageBookedValue;

    @Override
    public void beforeWrite() {
        clickThroughRate = percentage(clicks, impressions);
        costPerClick = divide(spend, clicks);
        costPerLead = divide(spend, leads);
        costPerQualifiedLead = divide(spend, qualifiedLeads);
        costPerMeeting = divide(spend, meetings);
        costPerBooking = divide(spend, bookings);
        qualificationRate = percentage(qualifiedLeads, leads);
        closeRate = percentage(bookings, qualifiedLeads);
        averageBookedValue = divide(revenue, bookings);
        // Organic and referral carry no spend, and dividing by zero there is not "infinite return",
        // it is "return on ad spend does not apply". Leave it blank rather than print a fiction.
        returnOnAdSpend = (spend == null || spend.signum() == 0 || revenue == null)
                ? null
                : revenue.divide(spend, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal divide(BigDecimal amount, Integer count) {
        if (amount == null || count == null || count == 0) return null;
        return amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentage(Integer part, Integer whole) {
        if (part == null || whole == null || whole == 0) return null;
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }

    public LocalDate getPeriod() { return period; }
    public void setPeriod(LocalDate period) { this.period = period; }
    public MarketingSource getSource() { return source; }
    public void setSource(MarketingSource source) { this.source = source; }
    public UtmMedium getMedium() { return medium; }
    public void setMedium(UtmMedium medium) { this.medium = medium; }
    public String getUtmSource() { return utmSource; }
    public void setUtmSource(String utmSource) { this.utmSource = utmSource; }
    public String getCampaign() { return campaign; }
    public void setCampaign(String campaign) { this.campaign = campaign; }
    public BigDecimal getSpend() { return spend; }
    public void setSpend(BigDecimal spend) { this.spend = spend; }
    public Integer getImpressions() { return impressions; }
    public void setImpressions(Integer impressions) { this.impressions = impressions; }
    public Integer getClicks() { return clicks; }
    public void setClicks(Integer clicks) { this.clicks = clicks; }
    public Integer getLeads() { return leads; }
    public void setLeads(Integer leads) { this.leads = leads; }
    public Integer getQualifiedLeads() { return qualifiedLeads; }
    public void setQualifiedLeads(Integer qualifiedLeads) { this.qualifiedLeads = qualifiedLeads; }
    public Integer getVipLeads() { return vipLeads; }
    public void setVipLeads(Integer vipLeads) { this.vipLeads = vipLeads; }
    public Integer getMeetings() { return meetings; }
    public void setMeetings(Integer meetings) { this.meetings = meetings; }
    public Integer getBookings() { return bookings; }
    public void setBookings(Integer bookings) { this.bookings = bookings; }
    public BigDecimal getRevenue() { return revenue; }
    public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    public BigDecimal getBookedWeddingValue() { return bookedWeddingValue; }
    public void setBookedWeddingValue(BigDecimal bookedWeddingValue) { this.bookedWeddingValue = bookedWeddingValue; }
    public BigDecimal getPipeline() { return pipeline; }
    public void setPipeline(BigDecimal pipeline) { this.pipeline = pipeline; }
    public BigDecimal getClickThroughRate() { return clickThroughRate; }
    public void setClickThroughRate(BigDecimal clickThroughRate) { this.clickThroughRate = clickThroughRate; }
    public BigDecimal getCostPerClick() { return costPerClick; }
    public void setCostPerClick(BigDecimal costPerClick) { this.costPerClick = costPerClick; }
    public BigDecimal getCostPerLead() { return costPerLead; }
    public void setCostPerLead(BigDecimal costPerLead) { this.costPerLead = costPerLead; }
    public BigDecimal getCostPerQualifiedLead() { return costPerQualifiedLead; }
    public void setCostPerQualifiedLead(BigDecimal costPerQualifiedLead) { this.costPerQualifiedLead = costPerQualifiedLead; }
    public BigDecimal getCostPerMeeting() { return costPerMeeting; }
    public void setCostPerMeeting(BigDecimal costPerMeeting) { this.costPerMeeting = costPerMeeting; }
    public BigDecimal getCostPerBooking() { return costPerBooking; }
    public void setCostPerBooking(BigDecimal costPerBooking) { this.costPerBooking = costPerBooking; }
    public BigDecimal getQualificationRate() { return qualificationRate; }
    public void setQualificationRate(BigDecimal qualificationRate) { this.qualificationRate = qualificationRate; }
    public BigDecimal getCloseRate() { return closeRate; }
    public void setCloseRate(BigDecimal closeRate) { this.closeRate = closeRate; }
    public BigDecimal getReturnOnAdSpend() { return returnOnAdSpend; }
    public void setReturnOnAdSpend(BigDecimal returnOnAdSpend) { this.returnOnAdSpend = returnOnAdSpend; }
    public BigDecimal getAverageBookedValue() { return averageBookedValue; }
    public void setAverageBookedValue(BigDecimal averageBookedValue) { this.averageBookedValue = averageBookedValue; }
}
