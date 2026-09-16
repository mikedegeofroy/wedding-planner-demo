package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.LeadInquiry;
import org.springframework.stereotype.Component;
import su.onno.ui.ChartBuilder;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

/**
 * Everything known about the leads themselves: where they came from, what they were worth, how fast
 * they were answered, and what contact they have actually had with the brand.
 *
 * <p>{@link MarketingPerformancePage} answers "what should we spend on"; this page answers "what
 * kind of couple does each channel send, and what happens to them". The two are separate because
 * they are read by different people at different moments, and merging them produces a board nobody
 * can read in a meeting.</p>
 *
 * <p>The page charts <b>both</b> attribution models side by side. First touch is what created the
 * demand; last touch is what closed the click. Where they disagree — and on this dataset they do,
 * because press and organic send couples who come back through an ad later — the gap is the finding,
 * not a data error. A dashboard that picks one model quietly hands the credit to whichever half of
 * the funnel it chose.</p>
 */
@Component
public class LeadIntelligencePage implements Page {

    @Override
    public String route() {
        return "/lead-intelligence";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Lead intelligence");
        b.subtitle("Attribution, lead quality, response speed and engagement — per source, campaign and channel.");

        b.widget("Time range").type("timeRange").width("full").order(-10)
                .config("presets", "30d,90d,6M,1y,all")
                .config("default", "1y");

        // ---- the funnel ------------------------------------------------------------------------------
        // Counts, not rates. A KPI tile totals the series behind it, which is correct for a count of
        // leads and silently wrong for a percentage; every rate on this page is a chart, where each
        // bucket is averaged server-side.
        b.widget("Inquiries").type("stat").width("1/5").order(0).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count").config("comparison", "true");

        b.widget("Qualified").type("stat").width("1/5").order(1).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "qualified = true").config("comparison", "true");

        b.widget("Met").type("stat").width("1/5").order(2).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "met = true").config("comparison", "true")
                .hint("Reached a call, a video meeting or a venue visit.");

        b.widget("Booked").type("stat").width("1/5").order(3).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "booked = true").config("comparison", "true");

        b.widget("VIP (€500k+)").type("stat").width("1/5").order(4).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "vip = true").config("comparison", "true");

        // The funnel itself, ahead of the charts that report its parts. The tiles above are the same
        // steps as counts; this is the one place the business can read what falls out between them
        // and what that costs, which no count and no rate on this page can say on its own.
        b.widget("Where the leads go").type("plannerLeadFunnel").width("full").order(5)
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("by", "source")
                .hint("Every step from inquiry to signature, the couples who fell out between two "
                        + "steps, and what their weddings were worth. Leads still in play are counted "
                        + "as open rather than lost.");

        b.chart("Conversion rates by month", LeadInquiry.class).width("full").order(9)
                .time(LeadInquiry::getDate, ChartBuilder.TimeBucket.MONTH)
                .average(LeadInquiry::getQualifiedRate).line().label("Qualified %").color("warning").unit("%")
                .secondary("Booked %", m -> m.average(LeadInquiry::getBookingRate)
                        .line().color("success").unit("%"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).maximum(100).label("Qualified %"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Booked %"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("The funnel over time. A rising qualification rate with a flat booking rate is a "
                        + "sales problem, not a targeting one.");

        b.chart("Leads by pipeline stage", LeadInquiry.class).width("full").order(10)
                .category(LeadInquiry::getStage)
                .count().bar()
                .secondary("Value", m -> m.sum(LeadInquiry::getBudget).line().color("success").unit("€"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Leads"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Value"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("The funnel with money on it: a wide stage holding little value is not a good stage.");

        // ---- attribution: the same question asked two ways ---------------------------------------------
        b.chart("Leads by first touch", LeadInquiry.class).width("1/2").order(20)
                .category(LeadInquiry::getSource)
                .count().bar().color("primary")
                .hint("The channel that created the demand — where the couple first heard of the studio.");

        b.chart("Leads by last touch", LeadInquiry.class).width("1/2").order(21)
                .category(LeadInquiry::getLastTouchSource)
                .count().bar().color("warning")
                .hint("The channel that produced the inquiry. This is what the ad platforms bill for.");

        b.chart("Bookings by first touch", LeadInquiry.class).width("1/2").order(22)
                .category(LeadInquiry::getSource)
                .count().bar().color("success")
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Bookings"))
                .filter("booked = true")
                .hint("Compare with last touch: a channel that only ever appears here is being undercredited.");

        b.chart("Fee revenue by first touch", LeadInquiry.class).width("1/2").order(23)
                .category(LeadInquiry::getSource)
                .sum(LeadInquiry::getBookedFee).bar().color("success").unit("€")
                .hint("Where the fee revenue the planner has actually signed originally came from.");

        // ---- lead quality per channel -------------------------------------------------------------------
        b.chart("Budget mix by source", LeadInquiry.class).width("full").order(30)
                .category(LeadInquiry::getSource)
                .seriesBy(LeadInquiry::getBudgetBand)
                .count().bar().stacked()
                .legend(ChartBuilder.Legend.TOP)
                .hint("Volume is not quality: this is the chart that shows which sources send €500k couples.");

        b.chart("Conversion by source", LeadInquiry.class).width("full").order(31)
                .category(LeadInquiry::getSource)
                .average(LeadInquiry::getQualifiedRate).bar().label("Qualified %").color("warning").unit("%")
                .secondary("Booked %", m -> m.average(LeadInquiry::getBookingRate)
                        .bar().color("success").unit("%"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).maximum(100).label("Qualified %"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Booked %"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("Which source converts best at each step — the question spend should follow.");

        b.chart("Average budget by source", LeadInquiry.class).width("1/2").order(40)
                .category(LeadInquiry::getSource)
                .average(LeadInquiry::getBudget).bar().unit("€")
                .hint("Across inquiries that stated a budget. Silent inquiries are not counted as zero.");

        b.chart("Leads by landing page", LeadInquiry.class).width("1/2").order(41)
                .category(LeadInquiry::getLandingPage)
                .count().bar()
                .hint("The page the couple was on when they wrote. Ad copy and landing page are one test.");

        // ---- speed to lead ---------------------------------------------------------------------------------
        b.chart("Speed to lead", LeadInquiry.class).width("1/2").order(50)
                .category(LeadInquiry::getResponseBand)
                .count().donut()
                .hint("Minutes between the inquiry arriving and the first human reply.");

        b.chart("Speed to lead by month", LeadInquiry.class).width("1/2").order(52)
                .time(LeadInquiry::getDate, ChartBuilder.TimeBucket.MONTH)
                .average(LeadInquiry::getResponseMinutes).line().color("warning").unit(" min")
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Minutes"))
                .hint("The one acquisition number the team controls directly. Watch it drift upward "
                        + "in the months when inquiry volume peaks.");

        b.chart("Booking rate by response speed", LeadInquiry.class).width("1/2").order(51)
                .category(LeadInquiry::getResponseBand)
                .average(LeadInquiry::getBookingRate).bar().color("success").unit("%")
                .hint("The standard finding, testable here: leads answered inside the hour book more often.");

        // ---- engagement: what contact the couple actually had ------------------------------------------------
        b.chart("Contact depth by source", LeadInquiry.class).width("1/2").order(60)
                .category(LeadInquiry::getSource)
                .average(LeadInquiry::getTouchCount).bar().label("Touches per lead").color("primary")
                .secondary("Meetings and visits", m -> m.average(LeadInquiry::getMeetingTouches)
                        .bar().color("chart-4"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("How much conversation a channel's couples need before they commit.");

        b.chart("Booking rate by number of touches", LeadInquiry.class).width("1/2").order(61)
                .category(LeadInquiry::getTouchCount)
                .average(LeadInquiry::getBookingRate).bar().color("success").unit("%")
                .hint("Engagement against outcome. Read it as correlation: booked leads keep talking.");

        b.chart("Channel first used", LeadInquiry.class).width("1/2").order(70)
                .category(LeadInquiry::getChannel)
                .count().donut()
                .hint("Instagram, WhatsApp, the site form or email — how the couple chose to reach us.");

        b.chart("Why leads were lost", LeadInquiry.class).width("1/2").order(71)
                .category(LeadInquiry::getLostReason)
                .count().bar().color("destructive")
                .filter("lost = true")
                .hint("Losing on budget is a targeting problem; losing to a competitor is a positioning one.");

        // ---- the leads themselves ------------------------------------------------------------------------------
        b.row(r -> {
            r.col("1/2", c -> c.widget("Most valuable open leads").type("list").width("full")
                    .document(LeadInquiry.class).maxItems(10)
                    .config("filter", "qualified = true AND active = true")
                    .config("titleTemplate", "{coupleName} · {preferredLocation}")
                    .config("secondaryField", "sourceDisplay,campaign")
                    .config("amountField", "budget").config("unit", "€"));
            r.col("1/2", c -> c.widget("Waiting on us").type("list").width("full")
                    .document(LeadInquiry.class).maxItems(10)
                    .config("filter", "qualified = false AND active = true")
                    .config("titleTemplate", "{coupleName}")
                    .config("secondaryField", "aiSummary")
                    .hint("Inquiries still missing a budget, a date or a location."));
        });

        // The full lead list, opened on the attribution view: grouped by source, newest first. Every
        // filter and column the list declares is still there — this is a starting point, not a cage.
        b.list(LeadInquiry.class, v -> v
                .groupBy("source")
                .sort("_date", true));
    }
}
