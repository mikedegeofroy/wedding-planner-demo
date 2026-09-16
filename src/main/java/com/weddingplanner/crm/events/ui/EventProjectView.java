package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import com.weddingplanner.crm.events.domain.*;

@Component public class EventProjectView implements EntityView<EventProject> {
 public Class<EventProject> entity(){return EventProject.class;}
 public void list(ListSpec<EventProject> list){list.columns(EventProject::getDescription,EventProject::getClient,EventProject::getStartDate,EventProject::getLocation,EventProject::getGuests,EventProject::getStage);}
 public void fields(EntityConfigBuilder<EventProject> f){

 f.field(EventProject::getDescription).label("Event name").order(0).width("full");
 f.field(EventProject::getClient).order(10).width("half");
 f.field(EventProject::getStage).order(11).width("half");
 f.field(EventProject::getStartDate).order(20).width("half");
 f.field(EventProject::getEndDate).order(21).width("half");
 f.field(EventProject::getLocation).order(30).width("half");
 f.field(EventProject::getGuests).order(31).width("half");
 f.field(EventProject::getSelectedBudget).hideInForm().hideInDetail();
 f.field(EventProject::getCurrency).hideInForm().hideInDetail();
 f.field(EventProject::getNotes).order(50).widget("textarea");
 f.relatedList("participants",EventParty.class).via(EventParty::getEvent).display(EventParty::getContact).columns(EventParty::getRole,EventParty::getScope).label("Participants");

 }
 public void actions(ActionSpec actions){
  // The money, the estimates and the crew live on the event's own page; the record form stays the
  // place where an event's facts are edited. Both surfaces link to each other.
  actions.action("openEventPage").label("Open event page").icon("layout-dashboard")
    .scope(ActionScope.DETAIL).navigate("onno://event?event={id}");
  actions.action("openEventPageRow").label("Open event page").icon("layout-dashboard")
    .scope(ActionScope.ROW).navigate("onno://event?event={id}");
 }
 public boolean comments(){return true;}

 /**
  * The event page: one event's money, estimates and crew on a route of its own, addressed by
  * {@code /event?event=<id>}. It is not in the nav — an event is reached from the Events list or
  * from the record — so it carries no section of its own.
  */
 @Component public static class EventPage implements Page {
  public String route(){return "/event";}
  public void compose(PageBuilder page){
   page.header(false);
   page.widget("Event").type("plannerEventPage").width("full");
  }
 }
}
