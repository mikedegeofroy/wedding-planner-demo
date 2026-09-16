package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.events.domain.*;

@Component public class EventPartyView implements EntityView<EventParty> {
 public Class<EventParty> entity(){return EventParty.class;}
 public void list(ListSpec<EventParty> list){list.columns(EventParty::getEvent,EventParty::getContact,EventParty::getRole,EventParty::getScope,EventParty::getCommissionRate);}
 public void fields(EntityConfigBuilder<EventParty> f){

 f.field(EventParty::getDescription).hideInForm().hideInDetail();
 f.field(EventParty::getEvent).order(0);
 // Role first, and the contact picker cascades on it: the catalog holds a card for every couple
 // who ever wrote in, so an unfiltered picker opens on fifty people sharing a first name and the
 // three suppliers are unreachable. Saying who you are adding first narrows the list to that
 // folder; changing it clears the contact rather than leaving a florist filed as the client.
 f.field(EventParty::getRole).order(10)
   .hint("Which of the contact folders this participant comes from. It picks the list below.");
 f.refField(EventParty::getContact).order(20)
   .refFilter("inbox_folder = ${role}")
   .refSecondary(Contact::getEmail)
   .hint("Only contacts filed under the role above. A new supplier is added from the Contacts catalog first.");
 f.field(EventParty::getScope).order(30);
 f.field(EventParty::getCommissionRate).order(40).width("half").placeholder("10")
   .hint("Only when this event agreed a different rate. Blank uses the rate on the contact card.");

 }

}
