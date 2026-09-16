package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
import su.onno.model.*;
import su.onno.types.Ref;
import su.onno.lifecycle.*;
import su.onno.rules.*;
import su.onno.posting.PostingContext;
import java.util.*;
import java.math.*;
import java.time.*;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.service.EventRules;

@Catalog(name="EventPartys",title="Event participants",codePrefix="EP-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class EventParty extends CatalogObject {
    @Attribute(displayName="Event",required=true) private Ref<EventProject> event;
    @Attribute(displayName="Contact",required=true) private Ref<Contact> contact;
    /**
     * What this person is to the event, drawn from the same {@link InboxFolder} the contact card is
     * filed under rather than a participant-only enum of its own. A person is a client, a supplier,
     * a venue or one of the planner's own team once, on their card; saying it a second time here in
     * a different vocabulary let the two disagree, and — because a reference picker can only cascade
     * on a field of the same enumeration — left the contact picker offering all 1,500 cards at once,
     * which is unusable when the list is couples who share a first name.
     */
    @Attribute(displayName="Role",required=true) private InboxFolder role = InboxFolder.CONTRACTORS;
    @Attribute(displayName="Responsibilities",length=1000) private String scope;
    /**
     * What this supplier rebates to Wedding Planner on this event, overriding the standing rate on their
     * contact card. A venue that normally pays 12% may pay 15% on a three-day booking, and the
     * deal belongs to the event rather than rewriting the supplier's standing terms. Blank falls
     * back to the contact.
     */
    @Attribute(displayName="Commission to the planner, %",precision=5,scale=2,min=0,max=100) private BigDecimal commissionRate;
    public Ref<EventProject> getEvent() { return event; }
    public void setEvent(Ref<EventProject> value) { event=value; }
    public Ref<Contact> getContact() { return contact; }
    public void setContact(Ref<Contact> value) { contact=value; }
    public InboxFolder getRole() { return role; }
    public void setRole(InboxFolder value) { role=value; }
    public String getScope() { return scope; }
    public void setScope(String value) { scope=value; }
    public BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(BigDecimal value) { commissionRate=value; }

}
