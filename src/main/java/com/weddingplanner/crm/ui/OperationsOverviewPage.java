package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.events.domain.EventInvoice;
import com.weddingplanner.crm.events.domain.EventPayment;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.domain.EventStage;
import com.weddingplanner.crm.events.domain.InvoiceDirection;
import org.springframework.stereotype.Component;
import su.onno.repository.EnumerationPersistence;
import su.onno.ui.ChartBuilder;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

/**
 * The home board: the whole of Wedding Planner on one screen — demand coming in, pipeline in play, events in
 * production, and the cash they generate.
 *
 * <p>It deliberately reports each part of the business in the same time window, because the questions
 * that matter are the ones that cross the seams: enquiries are up but is anything booking, the
 * pipeline looks healthy but has anyone invoiced it. Marketing's own numbers live on their own pages
 * — spend and channel economics are a different job than running this quarter — and this page links
 * to them rather than competing with them.</p>
 */
@Component
public class OperationsOverviewPage implements Page {

    private static final String CLIENT_INVOICE =
            EnumerationPersistence.resolveId(InvoiceDirection.class, InvoiceDirection.CLIENT).toString();
    private static final String CONFIRMED_EVENT =
            EnumerationPersistence.resolveId(EventStage.class, EventStage.CONFIRMED).toString();

    @Override
    public String route() {
        return "/";
    }

    @Override
    public void compose(PageBuilder b) {
        b.title("Operations");
        b.subtitle("Demand, pipeline, production and cash for the selected period.");

        b.widget("Time range").type("timeRange").width("full").order(-10)
                .config("presets", "30d,90d,6M,1y,all")
                .config("default", "90d");

        // ---- demand ------------------------------------------------------------------------------
        b.widget("Inquiries").type("stat").width("1/4").order(0).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("comparison", "true").config("comparisonLabel", "vs previous period")
                .hint("Every inquiry received in the period, qualified or not.");

        b.widget("Qualified").type("stat").width("1/4").order(1).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "qualified = true").config("comparison", "true")
                .hint("Budget, date and location on file, and at or above the €300k target.");

        b.widget("Bookings").type("stat").width("1/4").order(2).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "booked = true").config("comparison", "true")
                .hint("Inquiries from this period that have since signed.");

        b.widget("Booked value").type("stat").width("1/4").order(3).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getBookedValue)
                .config("unit", "€").config("comparison", "true")
                .hint("Wedding budgets of the inquiries that booked, credited to the month they arrived.");

        // ---- pipeline ----------------------------------------------------------------------------
        b.widget("Open pipeline").type("stat").width("1/4").order(10).rowBreak()
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getPipelineValue)
                .config("unit", "€")
                .hint("Budgets of qualified leads still in play — booked and lost are excluded.");

        b.widget("Fee revenue").type("stat").width("1/4").order(11)
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getBookedFee)
                .config("unit", "€").config("comparison", "true")
                .hint("Planning fees on what booked — the wedding budgets are the tile to the left.");

        b.widget("Meetings held").type("stat").width("1/4").order(12).document(LeadInquiry.class)
                .dateField(LeadInquiry::getDate).config("metric", "count")
                .config("filter", "met = true").config("comparison", "true");

        b.widget("Attributed ad cost").type("stat").width("1/4").order(13)
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("metric", "sum").metricField(LeadInquiry::getAcquisitionCost)
                .config("unit", "€").config("comparison", "true")
                .hint("Ad spend carried by the period's leads. Marketing performance breaks it down by channel.");

        // ---- the funnel --------------------------------------------------------------------------
        // The tiles above are the funnel's rungs as separate counts; this is the drop between them,
        // with the money attached. It sits directly under them because "we took 140 inquiries and
        // booked 9" is the question a reader has already started asking by this point in the page.
        b.widget("Where the leads go").type("plannerLeadFunnel").width("full").order(15)
                .document(LeadInquiry.class).dateField(LeadInquiry::getDate)
                .config("by", "source")
                .hint("Inquiry to signature, step by step: who fell out where, what it was worth, and "
                        + "how much of the gap is loss rather than work still in progress.");

        // ---- shape of the period ------------------------------------------------------------------
        b.chart("Inquiries and booked value", LeadInquiry.class).width("full").order(20)
                .time(LeadInquiry::getDate, ChartBuilder.TimeBucket.MONTH)
                .count().bar().label("Inquiries").color("primary")
                .secondary("Booked value", m -> m.sum(LeadInquiry::getBookedValue)
                        .line().color("success").unit("€"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).label("Inquiries"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Booked value"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("Volume against value: a good month for inquiries is not automatically a good month.");

        b.chart("Qualification and booking rate by month", LeadInquiry.class).width("full").order(21)
                .time(LeadInquiry::getDate, ChartBuilder.TimeBucket.MONTH)
                .average(LeadInquiry::getQualifiedRate).line().label("Qualified %").color("warning").unit("%")
                .secondary("Booked %", m -> m.average(LeadInquiry::getBookingRate)
                        .line().color("success").unit("%"))
                .axis(ChartBuilder.Axis.LEFT, a -> a.minimum(0).maximum(100).label("Qualified %"))
                .axis(ChartBuilder.Axis.RIGHT, a -> a.minimum(0).label("Booked %"))
                .legend(ChartBuilder.Legend.TOP)
                .hint("Rates are charted rather than tiled: a KPI tile totals its series, which is "
                        + "right for a count of leads and wrong for a percentage.");

        b.chart("Pipeline value by stage", LeadInquiry.class).width("1/2").order(30)
                .category(LeadInquiry::getStage)
                .sum(LeadInquiry::getBudget).bar().unit("€")
                .filter("active = true")
                .hint("What the open pipeline is worth at each step. Booked and lost leads are out.");

        b.chart("Events by stage", EventProject.class).width("1/2").order(31)
                .category(EventProject::getStage)
                .count().donut()
                .hint("Everything in production, from planning through delivery.");

        // ---- cash ---------------------------------------------------------------------------------
        b.widget("Invoiced to clients").type("stat").width("1/3").order(40).rowBreak()
                .document(EventInvoice.class).dateField(EventInvoice::getDate)
                .config("metric", "sum").metricField(EventInvoice::getTotal)
                .config("unit", "€")
                .config("filter", "direction = '" + CLIENT_INVOICE + "' AND _posted = true")
                .hint("Posted client invoices — supplier bills are not counted here.");

        b.widget("Received from clients").type("stat").width("1/3").order(41)
                .document(EventPayment.class).dateField(EventPayment::getDate)
                .config("metric", "sum").metricField(EventPayment::getAmount)
                .config("unit", "€")
                .config("filter", "direction = '" + CLIENT_INVOICE + "' AND _posted = true");

        b.widget("Confirmed events").type("stat").width("1/3").order(42)
                .catalog(EventProject.class)
                .config("metric", "count")
                .config("filter", "stage = '" + CONFIRMED_EVENT + "'")
                .hint("Signed events, at any date — this tile ignores the period picker.");

        // ---- what happens next ---------------------------------------------------------------------
        b.widget("Wedding calendar").type("calendar").width("full").order(50)
                .document(LeadInquiry.class)
                .dateField(LeadInquiry::getWeddingDate)
                .titleField(LeadInquiry::getCoupleName)
                .config("filter", "booked = true")
                .config("secondaryField", "preferredLocation")
                .config("amountField", "budget").config("unit", "€")
                .hint("Signed weddings on their wedding date, not their inquiry date.");

        b.row(r -> {
            r.col("1/2", c -> c.widget("Highest-value open leads").type("list").width("full")
                    .document(LeadInquiry.class).maxItems(8)
                    .config("filter", "qualified = true AND active = true")
                    .config("titleTemplate", "{coupleName} · {preferredLocation}")
                    .config("secondaryField", "stageDisplay,sourceDisplay")
                    .config("amountField", "budget").config("unit", "€"));
            r.col("1/2", c -> c.widget("Slowest first replies").type("list").width("full")
                    .document(LeadInquiry.class).maxItems(8)
                    .config("filter", "response_minutes > 120")
                    .config("titleTemplate", "{coupleName}")
                    .config("secondaryField", "responseBandDisplay,channelDisplay")
                    .hint("Speed to lead is the one acquisition number the team controls directly."));
        });

        b.list(EventProject.class);
    }
}
