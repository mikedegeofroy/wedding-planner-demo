package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.domain.LeadTouchpoint;
import com.weddingplanner.crm.domain.Qualification;
import org.springframework.stereotype.Component;
import su.onno.ui.EntityConfigBuilder;
import su.onno.ui.EntityView;
import su.onno.ui.ListSpec;

@Component
public class LeadInquiryView implements EntityView<LeadInquiry> {
    private final com.weddingplanner.crm.service.InquiryInboxService inbox;
    public LeadInquiryView(com.weddingplanner.crm.service.InquiryInboxService inbox) { this.inbox = inbox; }

    @Override
    public void actions(su.onno.ui.ActionSpec actions) {
        actions.action("addToInbox").label("Add to inbox").icon("messages-square")
                .scope(su.onno.ui.ActionScope.DETAIL).roles("MANAGER", "ADMIN")
                .handler(ctx -> {
                    inbox.importInquiry(ctx.id());
                    return su.onno.ui.ActionResult.refresh(su.onno.ui.ActionToast.success("Inquiry is available in Conversations"));
                });
    }

    @Override
    public Class<LeadInquiry> entity() {
        return LeadInquiry.class;
    }

    @Override
    public boolean comments() {
        return true;
    }

    @Override
    public void list(ListSpec<LeadInquiry> list) {
        list.columns(LeadInquiry::getCoupleName, LeadInquiry::getQualification,
                        LeadInquiry::getBudget, LeadInquiry::getWeddingDate,
                        LeadInquiry::getPreferredLocation, LeadInquiry::getSource,
                        LeadInquiry::getChannel, LeadInquiry::getStage, LeadInquiry::getOwner)
                .label(LeadInquiry::getCoupleName, "Couple")
                .label(LeadInquiry::getQualification, "Qualification")
                .label(LeadInquiry::getBudget, "Budget")
                .label(LeadInquiry::getWeddingDate, "Wedding date")
                .label(LeadInquiry::getPreferredLocation, "Location")
                .label(LeadInquiry::getSource, "Marketing source")
                .label(LeadInquiry::getChannel, "Channel")
                .label(LeadInquiry::getStage, "Stage")
                .label(LeadInquiry::getOwner, "Owner")
                .sortBy(LeadInquiry::getDate, true)
                .groupable(LeadInquiry::getStage, LeadInquiry::getQualification,
                        LeadInquiry::getSource, LeadInquiry::getLastTouchSource,
                        LeadInquiry::getUtmMedium, LeadInquiry::getCampaign,
                        LeadInquiry::getBudgetBand, LeadInquiry::getResponseBand)
                .defaultGroupBy(LeadInquiry::getStage)
                // What each pipeline stage is worth: the budget subtotal rides on every group
                // header, so the stage bands read as money, not just a lead count.
                .aggregate(LeadInquiry::getBudget, ListSpec.Agg.SUM, "Pipeline value")
                // Stage bands are the point of this list, so open them rather than making the
                // reviewer click six headers to see the pipeline.
                .groupsExpanded(true);

        list.filter(LeadInquiry::getQualification).label("Qualification").multiOptions();
        list.filter(LeadInquiry::getStage).label("Stage").multiOptions();
        list.filter(LeadInquiry::getSource).label("First touch").multiOptions();
        list.filter(LeadInquiry::getLastTouchSource).label("Last touch").multiOptions();
        list.filter(LeadInquiry::getUtmMedium).label("utm_medium").multiOptions();
        list.filter(LeadInquiry::getChannel).label("Channel").multiOptions();
        list.filter(LeadInquiry::getResponseBand).label("Speed to lead").multiOptions();
        list.filter(LeadInquiry::getBudgetBand).label("Budget band").multiOptions();
        list.filter(LeadInquiry::getWeddingDate).label("Wedding date").dateRange();

        list.rowStyle(row -> {
            Qualification q = row.enumValue(LeadInquiry::getQualification, Qualification.class);
            // The derived role, not the stage itself: a renamed or newly added lost stage still
            // greys its row out, and the list needs no join to the stage catalog to decide.
            StageRole role = row.enumValue(LeadInquiry::getStageRole, StageRole.class);
            if (role != null && role.lost()) return ListSpec.RowStyle.MUTED;
            if (q == Qualification.VIP) return ListSpec.RowStyle.ACCENT;
            if (q == Qualification.NEEDS_INFORMATION) return ListSpec.RowStyle.WARNING;
            return null;
        });
    }

    @Override
    public void fields(EntityConfigBuilder<LeadInquiry> f) {
        f.field(LeadInquiry::getNumber).label("Lead #").hideInForm()
                .field(LeadInquiry::getDate).label("Received at").format("dd MMM yyyy HH:mm")
                .field(LeadInquiry::isPosted).hideInForm().hideInList().hideInDetail();

        f.field(LeadInquiry::getContact).order(9).group("Contact").width("full");
        f.field(LeadInquiry::getCoupleName).order(10).group("Contact").width("half")
                .field(LeadInquiry::getEmail).order(11).group("Contact").width("half")
                .field(LeadInquiry::getPhone).order(12).group("Contact").width("half")
                .field(LeadInquiry::getChannel).order(13).group("Contact").width("half");

        f.field(LeadInquiry::getBudget).order(20).group("Wedding").width("half").format("currency:EUR")
                .field(LeadInquiry::getWeddingDate).order(21).group("Wedding").width("half").format("dd MMM yyyy")
                .field(LeadInquiry::getPreferredLocation).order(22).group("Wedding").width("half")
                .field(LeadInquiry::getGuestCount).order(23).group("Wedding").width("half").format("integer");

        // Attribution as the analytics tools write it: the two models, then the raw UTM triple the
        // click arrived on. Keeping the literal utm_* names is deliberate — this is the vocabulary
        // the ad accounts and the reports use, and translating it here would only cost a lookup.
        f.field(LeadInquiry::getSource).order(30).group("Attribution").width("half")
                .field(LeadInquiry::getLastTouchSource).order(31).group("Attribution").width("half")
                .hint("The last channel to touch the couple before they wrote. Derived from the touch history.")
                .field(LeadInquiry::getUtmSource).order(32).group("Attribution").width("half")
                .field(LeadInquiry::getUtmMedium).order(33).group("Attribution").width("half")
                .field(LeadInquiry::getCampaign).order(34).group("Attribution").width("half")
                .field(LeadInquiry::getUtmContent).order(35).group("Attribution").width("half")
                .field(LeadInquiry::getUtmTerm).order(36).group("Attribution").width("half")
                .field(LeadInquiry::getLandingPage).order(37).group("Attribution").width("half")
                .field(LeadInquiry::getReferrer).order(38).group("Attribution").width("half")
                .field(LeadInquiry::getBudgetBand).order(39).group("Attribution").hideInForm()
                .field(LeadInquiry::getQualification).order(40).group("Attribution").hideInForm();

        f.field(LeadInquiry::getStage).order(50).group("Routing").width("half")
                .hint("Picked from the pipeline the team keeps under Sales → Pipeline stages.")
                // Derived from the stage on every write; showing it as a second, editable stage
                // field would invite the two to disagree.
                .field(LeadInquiry::getStageRole).order(50).group("Routing").hideInForm().hideInList()
                .field(LeadInquiry::getOwner).order(51).group("Routing").width("half").hideInForm()
                .field(LeadInquiry::getLostReason).order(52).group("Routing").width("half")
                .hint("Only kept while the lead is lost; reopening it clears the reason.")
                .field(LeadInquiry::isQualified).order(53).group("Routing").hideInForm().hideInList()
                .field(LeadInquiry::isVip).order(54).group("Routing").hideInForm().hideInList()
                .field(LeadInquiry::isBooked).order(55).group("Routing").hideInForm().hideInList()
                .field(LeadInquiry::isLost).order(56).group("Routing").hideInForm().hideInList()
                .field(LeadInquiry::isMet).order(57).group("Routing").hideInForm().hideInList()
                .field(LeadInquiry::isActive).order(58).group("Routing").hideInForm().hideInList();

        f.field(LeadInquiry::getFirstResponseAt).order(60).group("Activity").width("half")
                .format("dd MMM yyyy HH:mm")
                .field(LeadInquiry::getMeetingAt).order(61).group("Activity").width("half")
                .format("dd MMM yyyy HH:mm")
                .field(LeadInquiry::getBookedAt).order(62).group("Activity").width("half")
                .format("dd MMM yyyy HH:mm").hideInForm()
                .field(LeadInquiry::getResponseBand).order(63).group("Activity").width("half").hideInForm()
                .field(LeadInquiry::getResponseMinutes).order(64).group("Activity").width("half")
                .hideInForm().hideInList().format("integer")
                .field(LeadInquiry::getDaysToBooking).order(65).group("Activity").width("half")
                .hideInForm().hideInList().format("integer")
                .field(LeadInquiry::getOriginalMessage).order(66).group("Activity").width("full").widget("textarea")
                .field(LeadInquiry::getAiSummary).order(67).group("Activity").width("full").widget("textarea");

        // Rolled up from the touch history below, because a chart can only group by a header column.
        f.field(LeadInquiry::getTouchCount).order(70).group("Engagement").width("half").hideInForm().format("integer")
                .field(LeadInquiry::getInboundTouches).order(71).group("Engagement").width("half").hideInForm().hideInList().format("integer")
                .field(LeadInquiry::getOutboundTouches).order(72).group("Engagement").width("half").hideInForm().hideInList().format("integer")
                .field(LeadInquiry::getMeetingTouches).order(73).group("Engagement").width("half").hideInForm().hideInList().format("integer")
                .field(LeadInquiry::getFirstTouchAt).order(74).group("Engagement").width("half").hideInForm().hideInList()
                .format("dd MMM yyyy HH:mm")
                .field(LeadInquiry::getLastTouchAt).order(75).group("Engagement").width("half").hideInForm().hideInList()
                .format("dd MMM yyyy HH:mm")
                .field(LeadInquiry::getConsiderationDays).order(76).group("Engagement").width("half")
                .hideInForm().hideInList().format("integer")
                .hint("Days between the couple's first recorded touch and the inquiry itself.");

        f.field(LeadInquiry::getAcquisitionCost).order(80).group("Economics").width("half")
                .hideInForm().format("currency:EUR")
                .hint("This lead's share of its channel-month spend, pushed down by the marketing rollup.")
                .field(LeadInquiry::getPipelineValue).order(81).group("Economics").width("half")
                .hideInForm().hideInList().format("currency:EUR")
                .field(LeadInquiry::getBookedValue).order(82).group("Economics").width("half")
                .hideInForm().hideInList().format("currency:EUR")
                .field(LeadInquiry::getPipelineFee).order(83).group("Economics").width("half")
                .hideInForm().hideInList().format("currency:EUR")
                .field(LeadInquiry::getBookedFee).order(84).group("Economics").width("half")
                .hideInForm().format("currency:EUR")
                .hint("The planner's fee on this wedding — the assumed share of the couple's budget.")
                .field(LeadInquiry::getNetValue).order(85).group("Economics").width("half")
                .hideInForm().format("currency:EUR")
                .field(LeadInquiry::getQualifiedRate).order(86).group("Economics").hideInForm().hideInList().hideInDetail()
                .field(LeadInquiry::getMeetingRate).order(87).group("Economics").hideInForm().hideInList().hideInDetail()
                .field(LeadInquiry::getBookingRate).order(88).group("Economics").hideInForm().hideInList().hideInDetail();

        // The contact history itself. Everything above is a summary of these rows.
        f.rowField(LeadInquiry::getTouches, LeadTouchpoint::getAt).order(0).width("180");
        f.rowField(LeadInquiry::getTouches, LeadTouchpoint::getType).order(1).width("170");
        f.rowField(LeadInquiry::getTouches, LeadTouchpoint::getChannel).order(2).width("150");
        f.rowField(LeadInquiry::getTouches, LeadTouchpoint::getSource).order(3).width("180");
        f.rowField(LeadInquiry::getTouches, LeadTouchpoint::getDetail).order(4).width("320");

        f.action("post").hidden();
        f.action("unpost").hidden();
    }
}
