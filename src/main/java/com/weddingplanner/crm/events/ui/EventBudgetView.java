package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import su.onno.repository.EnumerationPersistence;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.*;

@Component public class EventBudgetView implements EntityView<EventBudget> {
 public Class<EventBudget> entity(){return EventBudget.class;}
 public void list(ListSpec<EventBudget> list){list.columns(EventBudget::getNumber,EventBudget::getEvent,EventBudget::getScenario,EventBudget::getRevision,EventBudget::getTotal,EventBudget::getUnknownItems);}
 public void fields(EntityConfigBuilder<EventBudget> f){

 f.field(EventBudget::getEvent).order(0).width("half");f.field(EventBudget::getScenario).order(1).width("half");
 f.field(EventBudget::getRevision).order(2).width("half");f.field(EventBudget::getPriceBasis).order(3).width("half");
 f.field(EventBudget::getTotal).format("currency:EUR").hideInForm();f.field(EventBudget::getUnknownItems).hideInForm();
 f.field(EventBudget::getCostTotal).format("currency:EUR").hideInForm()
   .hint("What the priced rows cost the planner. Rows with no cost entered are counted at their quoted price.");
 f.field(EventBudget::getRefundableDeposit).format("currency:EUR").hint("Refundable security deposit, excluded from the estimate subtotal.");
 f.field(EventBudget::getSourceFile).hideInForm();f.action("post").hidden();f.field(EventBudget::isPosted).hideInForm().hideInDetail().hideInList();
 // Column widths: Details is where the scope of a line actually gets written, so it gets the room.
 // The two enum pickers shrink to what their longest option needs, paying for most of it.
 f.rowField(EventBudget::getItems,EventBudgetLine::getPhase).order(0).width("150");
 f.rowRefField(EventBudget::getItems,EventBudgetLine::getArticle).order(1);
 f.rowField(EventBudget::getItems,EventBudgetLine::getDetails).order(2).widget("textarea").width("480");
 // A budget line is booked against a supplier — a contractor, a venue, a partner agency — never
 // against the couple paying for the wedding, so the picker drops the Clients folder instead of
 // offering every contact in the CRM. Enum columns store the constant's deterministic UUID, so the
 // predicate binds the resolved id the way ContactFolderPage does, not the literal "CLIENTS".
 f.rowRefField(EventBudget::getItems,EventBudgetLine::getContractor).order(3)
   .refFilter("inboxFolder != " + EnumerationPersistence.resolveId(InboxFolder.class, InboxFolder.CLIENTS));
 f.rowField(EventBudget::getItems,EventBudgetLine::getKind).order(4).width("170");
 f.rowField(EventBudget::getItems,EventBudgetLine::getPriceState).order(5).width("170");
 f.rowField(EventBudget::getItems,EventBudgetLine::getQuantity).order(6);
 f.rowField(EventBudget::getItems,EventBudgetLine::getUnitPrice).order(7).format("currency:EUR");
 // What Wedding Planner pays for the same row, when it is not simply passed on at the quoted price. Leaving
 // it blank reports no margin on the row rather than inventing one, so it is safe to ignore.
 f.rowField(EventBudget::getItems,EventBudgetLine::getUnitCost).order(8).format("currency:EUR");
 f.rowField(EventBudget::getItems,EventBudgetLine::getAmount).order(9).format("currency:EUR").hideInForm();
 f.rowField(EventBudget::getItems,EventBudgetLine::getCostAmount).order(10).format("currency:EUR").hideInForm();

 }

}
