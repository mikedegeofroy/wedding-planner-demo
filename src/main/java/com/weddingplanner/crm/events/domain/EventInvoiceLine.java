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


public class EventInvoiceLine extends TabularSectionRow {
    @Attribute(displayName="Budget article",required=true) private Ref<BudgetArticle> article;
    @Attribute(displayName="Event day",required=true) private EventPhase phase = EventPhase.WEDDING;
    @Attribute(displayName="Charge type",required=true) private ChargeKind kind = ChargeKind.SERVICE;
    @Attribute(displayName="Description",length=3000) private String details;
    @Attribute(displayName="Quantity",precision=15,scale=3) private BigDecimal quantity = BigDecimal.ONE;
    @Attribute(displayName="Unit amount incl. VAT (EUR)",precision=15,scale=2) private BigDecimal unitPrice = BigDecimal.ZERO;
    @Attribute(displayName="Amount incl. VAT (EUR)",precision=15,scale=2) private BigDecimal amount = BigDecimal.ZERO;
    public Ref<BudgetArticle> getArticle() { return article; }
    public void setArticle(Ref<BudgetArticle> value) { article=value; }
    public EventPhase getPhase() { return phase; }
    public void setPhase(EventPhase value) { phase=value; }
    public ChargeKind getKind() { return kind; }
    public void setKind(ChargeKind value) { kind=value; }
    public String getDetails() { return details; }
    public void setDetails(String value) { details=value; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { quantity=value; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { unitPrice=value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount=value; }

}
