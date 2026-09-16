package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.MarketingPerformance;
import com.weddingplanner.crm.service.MarketingRollupService;
import org.springframework.stereotype.Component;
import su.onno.ui.ActionResult;
import su.onno.ui.ActionToast;
import su.onno.ui.ChartBuilder;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

/**
 * What each acquisition channel costs and what it returns.
 *
 * <p>The page is built around one rule: a channel is judged on the <b>value of the couples it
 * produces</b>, not on how many it produces or how cheaply. Organic Instagram wins every
 * cost-per-lead comparison and referral wins every revenue comparison, and a board that only showed
 * the first would steadily talk Wedding Planner into buying cheaper, worse leads. So cost per lead, fee per
 * lead and the difference between them sit next to each other, in that order.</p>
 *
 * <p>Revenue here is Wedding Planner's <b>planning fee</b>, not the couple's wedding budget — see
 * {@code LeadInquiry}'s fee-rate constant, which is an assumption to confirm with the client. A
 * €250 lead set against a €450k wedding budget would make every cost on this page round to nothing,
 * and the page would quietly stop being about cost.</p>
 *
 * <p>Cost figures are exact rather than an average of averages: {@code MarketingRollupService}
 * spreads each channel-month's spend across the leads it produced and stores it on the lead, so an
 * average over any slice — a source, a campaign, a budget band — weights by the leads that slice
 * actually contains. See {@link LeadInquiry#getAcquisitionCost()}.</p>
 *
 * <p>Credit here follows <b>last touch</b>, the model the ad platforms bill against, so these
 * numbers are comparable with what Meta and Google report. The first-touch view — which channel
 * created the demand in the first place — lives on {@link LeadIntelligencePage}, charted against
 * this one.</p>
 */
@Component
public class MarketingPerformancePage implements Page {

    private final MarketingRollupService rollup;

    public MarketingPerformancePage(MarketingRollupService rollup) {
        this.rollup = rollup;
    }

    @Override
    public String route() {
        return "/marketing";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Marketing performance");
        b.subtitle("Spend, cost per lead and the fee revenue each channel returns. Credited last touch.");

        b.widget("Time range").type("timeRange").width("full").order(-10)
                .config("presets", "30d,90d,6M,1y,all")
                .config("default", "1y");

        // ---- the money in and the money out --------------------------------------------------------
        b.widget("Spend").type("stat").width("1/4").order(0).catalog(MarketingPerformance.class)
                .dateField(MarketingPerformance::getPeriod)
                .config("metric", "sum").metricField(MarketingPerformance::getSpend)
                .config("unit", "€").config("comparison", "true")
                .hint("Imported from the ad platforms. Referral and organic carry no spend by design.");

        b.widget("Leads").type("stat").width("1/4").order(1).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("comparison", "true");

        b.widget("Bookings").type("stat").width("1/4").order(2).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "booked = true").config("comparison", "true");

        b.widget("Fee revenue").type("stat").width("1/4").order(3).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getBookedFee)
                .config("unit", "€").config("comparison", "true")
                .hint("Planning fees on the weddings that booked, credited to the month the "
                        + "inquiry arrived. The couples' wedding budgets are reported separately.");

        b.widget("Qualified leads").type("stat").width("1/4").order(10).rowBreak()
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("metric", "count").config("filter", "qualified = true")
                .config("comparison", "true");

        b.widget("VIP leads").type("stat").width("1/4").order(11).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "vip = true").config("comparison", "true")
                .hint("€500k+ budgets — the couples a founder takes personally.");

        b.widget("Booked wedding value").type("stat").width("1/4").order(12)
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getBookedValue)
                .config("unit", "€").config("comparison", "true")
                .hint("What the couples will spend on their weddings — the production load, not planner revenue.");

        b.widget("Net after ad cost").type("stat").width("1/4").order(13)
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getNetValue)
                .config("unit", "€").config("comparison", "true")
                .hint("Fee revenue less the ad spend carried by the period's leads.");

        // Per-lead economics are charted, never tiled. A KPI tile totals its series — right for
        // spend and bookings, wrong for a cost per lead, which would come out multiplied by the
        // number of buckets in the window.
        b.chart("Cost and fee per lead by month", LeadInquiry.class).width("full").order(19)
                .time(LeadInquiry::getDate, ChartBuilder.TimeBucket.MONTH)
                .average(LeadInquiry::getAcquisitionCost).bar().label("Cost per lead")
                .color("primary").unit("€")
                .secondary("Fee per lead", m -> m.average(LeadInquiry::getBookedFee)
                        .line().color("success").unit("€"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Cost per lead"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Fee per lead"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("The two lines that decide the budget. Fee per lead is lumpy — one booking moves it.");

        // ---- trend ----------------------------------------------------------------------------------
        b.chart("Spend and leads by month", MarketingPerformance.class).width("full").order(20)
                .time(MarketingPerformance::getPeriod, ChartBuilder.TimeBucket.MONTH)
                .sum(MarketingPerformance::getSpend).bar().label("Spend").color("primary").unit("€")
                .secondary("Leads", m -> m.sum(MarketingPerformance::getLeads).line().color("success"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Spend"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Leads"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("Spend buying proportionally fewer leads over time is the first sign of fatigue.");

        // ---- the three channel comparisons that matter, in order ------------------------------------
        b.chart("Cost per lead by channel", LeadInquiry.class).width("1/3").order(30)
                .category(LeadInquiry::getLastTouchSource)
                .average(LeadInquiry::getAcquisitionCost).bar().unit("€")
                .hint("Cheapest first — and on its own, the least useful of these three charts.");

        b.chart("Fee per lead by channel", LeadInquiry.class).width("1/3").order(31)
                .category(LeadInquiry::getLastTouchSource)
                .average(LeadInquiry::getBookedFee).bar().color("success").unit("€")
                .hint("Fee revenue spread across every lead the channel sent, including the duds.");

        b.chart("Net per lead by channel", LeadInquiry.class).width("1/3").order(32)
                .category(LeadInquiry::getLastTouchSource)
                .average(LeadInquiry::getNetValue).bar().color("primary").unit("€")
                .hint("Fee minus cost: the ranking to spend against.");

        // ---- where the money goes --------------------------------------------------------------------
        b.chart("Spend by channel", MarketingPerformance.class).width("1/2").order(40)
                .category(MarketingPerformance::getSource)
                .sum(MarketingPerformance::getSpend).donut().unit("€");

        b.chart("Bookings by channel", LeadInquiry.class).width("1/2").order(41)
                .category(LeadInquiry::getLastTouchSource)
                .count().bar().color("success")
                .filter("booked = true")
                .hint("The denominator of cost per booking — check it is big enough to divide by.");

        b.chart("Leads by utm_medium", LeadInquiry.class).width("1/2").order(50)
                .category(LeadInquiry::getUtmMedium)
                .count().donut()
                .hint("Paid, owned and earned. The split that survives a channel being renamed.");

        b.chart("Leads by campaign", LeadInquiry.class).width("1/2").order(51)
                .category(LeadInquiry::getCampaign)
                .count().bar()
                .hint("utm_campaign as it arrived. Leads with no campaign are direct or organic.");

        b.chart("Qualification rate by campaign", LeadInquiry.class).width("full").order(60)
                .category(LeadInquiry::getCampaign)
                .average(LeadInquiry::getQualifiedRate).bar().unit("%").color("warning")
                .secondary("Booking rate", m -> m.average(LeadInquiry::getBookingRate)
                        .bar().color("success").unit("%"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Qualified %"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Booked %"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("A campaign that qualifies well but books badly is selling the wrong wedding.");

        b.actions("Data", a -> a.action("recomputeRollup")
                .label("Recalculate from leads").icon("refresh-cw")
                .roles("MANAGER", "ADMIN")
                .handler(ctx -> {
                    int rows = rollup.recompute();
                    return ActionResult.refresh(ActionToast.success(
                            "Recalculated " + rows + " channel rows from the current lead data"));
                }));

        // The full table underneath the charts: spend is editable, results are derived. Newest first,
        // grouped by channel, so a month's CPL can be read against the months either side of it.
        b.list(MarketingPerformance.class, v -> v
                .groupBy("source")
                .sort("period", true));
    }
}
