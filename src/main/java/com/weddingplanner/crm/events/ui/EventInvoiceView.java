package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import com.weddingplanner.crm.events.domain.*;

@Component public class EventInvoiceView implements EntityView<EventInvoice> {
 public Class<EventInvoice> entity(){return EventInvoice.class;}
 public void list(ListSpec<EventInvoice> list){list.columns(EventInvoice::getNumber,EventInvoice::getEvent,EventInvoice::getCounterparty,EventInvoice::getDirection,EventInvoice::getDueDate,EventInvoice::getTotal);}
 public void actions(ActionSpec actions){
  actions.action("recordPayment").label("Record payment").icon("banknote").scope(ActionScope.DETAIL)
   .visibleWhen(row -> row.bool("_posted"))
   .navigate("onno://documents/event_payments/new?invoice={id}");
 }
 public void fields(EntityConfigBuilder<EventInvoice> f){

 f.field(EventInvoice::getEvent).order(0).width("half");f.field(EventInvoice::getDirection).order(1).width("half");
 f.field(EventInvoice::getCounterparty).order(2).width("half");f.field(EventInvoice::getDueDate).order(3).width("half");
 f.field(EventInvoice::getExternalNumber).order(4);f.field(EventInvoice::getTotal).format("currency:EUR").hideInForm();
 f.field(EventInvoice::getNotes).widget("textarea").order(50);
 f.rowRefField(EventInvoice::getItems,EventInvoiceLine::getArticle).order(0);
 f.rowField(EventInvoice::getItems,EventInvoiceLine::getPhase).order(1);
 f.rowField(EventInvoice::getItems,EventInvoiceLine::getKind).order(2);
 f.rowField(EventInvoice::getItems,EventInvoiceLine::getDetails).order(3);
 f.rowField(EventInvoice::getItems,EventInvoiceLine::getQuantity).order(4);
 f.rowField(EventInvoice::getItems,EventInvoiceLine::getUnitPrice).order(5).format("currency:EUR");
 f.rowField(EventInvoice::getItems,EventInvoiceLine::getAmount).order(6).format("currency:EUR").hideInForm();

 }

}
