package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.MarketingPerformance;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

/**
 * The channel table. Spend, impressions and clicks are typed in (or imported); everything from
 * leads rightwards is recomputed from the lead documents, so the editable half and the derived half
 * are visibly different things — the derived columns are read-only in the form for the same reason
 * a cell with a formula in it is.
 */
@Component
public class MarketingPerformanceView implements EntityView<MarketingPerformance> {
    @Override
    public Class<MarketingPerformance> entity() {
        return MarketingPerformance.class;
    }

    @Override
    public void list(ListSpec<MarketingPerformance> list) {
        list.columns(MarketingPerformance::getPeriod, MarketingPerformance::getSource,
                        MarketingPerformance::getCampaign, MarketingPerformance::getSpend,
                        MarketingPerformance::getLeads, MarketingPerformance::getQualifiedLeads,
                        MarketingPerformance::getBookings, MarketingPerformance::getRevenue,
                        MarketingPerformance::getBookedWeddingValue,
                        MarketingPerformance::getCostPerLead,
                        MarketingPerformance::getCostPerQualifiedLead,
                        MarketingPerformance::getCostPerBooking,
                        MarketingPerformance::getReturnOnAdSpend)
                .label(MarketingPerformance::getPeriod, "Month")
                .label(MarketingPerformance::getSource, "Source")
                .label(MarketingPerformance::getCampaign, "Campaign")
                .label(MarketingPerformance::getSpend, "Spend")
                .label(MarketingPerformance::getLeads, "Leads")
                .label(MarketingPerformance::getQualifiedLeads, "Qualified")
                .label(MarketingPerformance::getBookings, "Bookings")
                .label(MarketingPerformance::getRevenue, "Fee revenue")
                .label(MarketingPerformance::getBookedWeddingValue, "Wedding value")
                .label(MarketingPerformance::getCostPerLead, "CPL")
                .label(MarketingPerformance::getCostPerQualifiedLead, "CPQL")
                .label(MarketingPerformance::getCostPerBooking, "CAC")
                .label(MarketingPerformance::getReturnOnAdSpend, "Fee per €1 spent")
                .sortBy(MarketingPerformance::getPeriod, true)
                .groupable(MarketingPerformance::getSource, MarketingPerformance::getMedium,
                        MarketingPerformance::getCampaign)
                .defaultGroupBy(MarketingPerformance::getSource)
                // A group header that totals its spend and its bookings is the only place the table
                // states a channel's real all-period cost per booking rather than a monthly mean.
                .aggregate(MarketingPerformance::getSpend, ListSpec.Agg.SUM, "Spend")
                .aggregate(MarketingPerformance::getLeads, ListSpec.Agg.SUM, "Leads")
                .aggregate(MarketingPerformance::getBookings, ListSpec.Agg.SUM, "Bookings")
                .aggregate(MarketingPerformance::getRevenue, ListSpec.Agg.SUM, "Fee revenue")
                .groupsExpanded(true);

        list.filter(MarketingPerformance::getSource).label("Source").multiOptions();
        list.filter(MarketingPerformance::getMedium).label("utm_medium").multiOptions();
        list.filter(MarketingPerformance::getPeriod).label("Month").dateRange();
    }

    @Override
    public void fields(EntityConfigBuilder<MarketingPerformance> f) {
        f.field(MarketingPerformance::getCode).label("Row #").hideInForm()
                .field(MarketingPerformance::getDescription).label("Label").hideInList();

        f.field(MarketingPerformance::getPeriod).order(10).width("half").format("MMM yyyy")
                .field(MarketingPerformance::getSource).order(11).width("half")
                .field(MarketingPerformance::getMedium).order(12).width("half")
                .field(MarketingPerformance::getUtmSource).order(13).width("half")
                .field(MarketingPerformance::getCampaign).order(14).width("full");

        f.field(MarketingPerformance::getSpend).order(20).group("Spend").width("half").format("currency:EUR")
                .field(MarketingPerformance::getImpressions).order(21).group("Spend").width("half").format("integer")
                .field(MarketingPerformance::getClicks).order(22).group("Spend").width("half").format("integer")
                .field(MarketingPerformance::getClickThroughRate).order(23).group("Spend").width("half")
                .hideInForm().hideInList()
                .field(MarketingPerformance::getCostPerClick).order(24).group("Spend")
                .hideInForm().hideInList().format("currency:EUR");

        // Derived from the leads. Editable here, they would be a second answer to a question the
        // rollup already answers — and the two would drift apart the first time anyone typed.
        f.field(MarketingPerformance::getLeads).order(30).group("Results").width("half").hideInForm().format("integer")
                .field(MarketingPerformance::getQualifiedLeads).order(31).group("Results").width("half").hideInForm().format("integer")
                .field(MarketingPerformance::getVipLeads).order(32).group("Results").width("half").hideInForm().format("integer")
                .field(MarketingPerformance::getMeetings).order(33).group("Results").width("half").hideInForm().format("integer")
                .field(MarketingPerformance::getBookings).order(34).group("Results").width("half").hideInForm().format("integer")
                .field(MarketingPerformance::getRevenue).order(35).group("Results").width("half").hideInForm().format("currency:EUR")
                .field(MarketingPerformance::getBookedWeddingValue).order(36).group("Results").width("half").hideInForm().format("currency:EUR")
                .field(MarketingPerformance::getPipeline).order(37).group("Results").width("half").hideInForm().format("currency:EUR");

        f.field(MarketingPerformance::getCostPerLead).order(40).group("Efficiency").width("half").hideInForm().format("currency:EUR")
                .field(MarketingPerformance::getCostPerQualifiedLead).order(41).group("Efficiency").width("half").hideInForm().format("currency:EUR")
                .field(MarketingPerformance::getCostPerMeeting).order(42).group("Efficiency").width("half").hideInForm().format("currency:EUR")
                .field(MarketingPerformance::getCostPerBooking).order(43).group("Efficiency").width("half").hideInForm().format("currency:EUR")
                .field(MarketingPerformance::getQualificationRate).order(44).group("Efficiency").width("half").hideInForm().hideInList()
                .field(MarketingPerformance::getCloseRate).order(45).group("Efficiency").width("half").hideInForm().hideInList()
                .field(MarketingPerformance::getAverageBookedValue).order(46).group("Efficiency").width("half").hideInForm().hideInList().format("currency:EUR")
                .field(MarketingPerformance::getReturnOnAdSpend).order(47).group("Efficiency").width("half").hideInForm()
                .hint("Planning-fee revenue per euro of spend. Blank for channels that cost nothing.");
    }
}
