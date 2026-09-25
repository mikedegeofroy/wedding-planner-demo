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
import com.weddingplanner.crm.events.service.EventRules;

@Catalog(name="EventProjects",title="Events",codePrefix="EV-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class EventProject extends CatalogObject implements Validated {
    @Attribute(displayName="Primary client") private Ref<Contact> client;
    @Attribute(displayName="Start date") private LocalDate startDate;
    @Attribute(displayName="End date") private LocalDate endDate;
    @Attribute(displayName="Guests",min=1) private Integer guests;
    @Attribute(displayName="Location") private String location;
    @Attribute(displayName="Stage") private EventStage stage = EventStage.PLANNING;
    @Attribute(displayName="Currency",length=3) private String currency = "EUR";
    @Attribute(displayName="Budget scenario") private Ref<EventBudget> selectedBudget;
    @Attribute(displayName="Notes",length=4000) private String notes;
    /**
     * The secret in the couple's link to their own page ({@code /client/event/<token>}). Blank means
     * the event was never shared; clearing it revokes every link handed out so far.
     */
    @Attribute(displayName="Client link token",length=64) private String shareToken;
    public Ref<Contact> getClient() { return client; }
    public void setClient(Ref<Contact> value) { client=value; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate value) { startDate=value; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate value) { endDate=value; }
    public Integer getGuests() { return guests; }
    public void setGuests(Integer value) { guests=value; }
    public String getLocation() { return location; }
    public void setLocation(String value) { location=value; }
    public EventStage getStage() { return stage; }
    public void setStage(EventStage value) { stage=value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String value) { currency=value; }
    public Ref<EventBudget> getSelectedBudget() { return selectedBudget; }
    public void setSelectedBudget(Ref<EventBudget> value) { selectedBudget=value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes=value; }
    public String getShareToken() { return shareToken; }
    public void setShareToken(String value) { shareToken=value; }

    @Override public List<BusinessRule> rules() { return List.of(
        new BusinessRule("dates","End date must not precede start date",()->startDate==null||endDate==null||!endDate.isBefore(startDate)),
        new BusinessRule("eur","The prototype uses EUR",()->"EUR".equals(currency)),
        new BusinessRule("budget","Choose a budget belonging to this event",()->selectedBudget==null||EventRules.budgetBelongs(selectedBudget,getId()))); }

}
