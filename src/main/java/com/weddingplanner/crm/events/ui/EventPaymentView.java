package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import com.weddingplanner.crm.events.domain.*;

@Component public class EventPaymentView implements EntityView<EventPayment> {
 public Class<EventPayment> entity(){return EventPayment.class;}
 public void list(ListSpec<EventPayment> list){list.columns(EventPayment::getNumber,EventPayment::getDate,EventPayment::getEvent,EventPayment::getInvoice,EventPayment::getDirection,EventPayment::getAmount);}
 public void fields(EntityConfigBuilder<EventPayment> f){

 f.field(EventPayment::getInvoice).order(0).hint("Choose a posted invoice. Post this payment to reduce its outstanding balance.");
 f.field(EventPayment::getAmount).order(10).format("currency:EUR");
 f.field(EventPayment::getEvent).hideInForm();f.field(EventPayment::getDirection).hideInForm();
 f.field(EventPayment::getReference).order(20);f.field(EventPayment::getNotes).order(30).widget("textarea");

 }

}
