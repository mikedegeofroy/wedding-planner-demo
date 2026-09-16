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


public class EventBudgetLine extends TabularSectionRow {
    @Attribute(displayName="Event day",required=true) private EventPhase phase = EventPhase.WEDDING;
    @Attribute(displayName="Budget article",required=true) private Ref<BudgetArticle> article;
    @Attribute(displayName="Details",length=6000) private String details;
    @Attribute(displayName="Contractor") private Ref<Contact> contractor;
    /**
     * What the line is, for the money: a supplier's service passed on to the couple, Wedding Planner's own
     * fee, or a refundable deposit that is only ever held. The three behave differently the moment
     * anyone asks what the event earns, so the estimate says which it is rather than leaving the
     * invoice to guess.
     */
    @Attribute(displayName="Charge type",required=true) private ChargeKind kind = ChargeKind.SERVICE;
    @Attribute(displayName="Price status",required=true) private BudgetPriceState priceState = BudgetPriceState.QUOTED;
    @Attribute(displayName="Quantity",precision=15,scale=3) private BigDecimal quantity = BigDecimal.ONE;
    @Attribute(displayName="Unit price (EUR)",precision=15,scale=2) private BigDecimal unitPrice;
    /**
     * What the supplier charges Wedding Planner for one unit, when it differs from what the couple is quoted.
     * Left blank it is not a missing number: a service line is assumed to be passed through at the
     * quoted price, and a planner fee line to cost nothing, so a blank column never invents margin.
     */
    @Attribute(displayName="Our cost / unit (EUR)",precision=15,scale=2) private BigDecimal unitCost;
    @Attribute(displayName="Amount (EUR)",precision=15,scale=2) private BigDecimal amount;
    @Attribute(displayName="Our cost (EUR)",precision=15,scale=2) private BigDecimal costAmount;
    public EventPhase getPhase() { return phase; }
    public void setPhase(EventPhase value) { phase=value; }
    public Ref<BudgetArticle> getArticle() { return article; }
    public void setArticle(Ref<BudgetArticle> value) { article=value; }
    public String getDetails() { return details; }
    public void setDetails(String value) { details=value; }
    public Ref<Contact> getContractor() { return contractor; }
    public void setContractor(Ref<Contact> value) { contractor=value; }
    public BudgetPriceState getPriceState() { return priceState; }
    public void setPriceState(BudgetPriceState value) { priceState=value; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { quantity=value; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { unitPrice=value; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal value) { unitCost=value; }
    public ChargeKind getKind() { return kind; }
    public void setKind(ChargeKind value) { kind=value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount=value; }
    public BigDecimal getCostAmount() { return costAmount; }
    public void setCostAmount(BigDecimal value) { costAmount=value; }

}
