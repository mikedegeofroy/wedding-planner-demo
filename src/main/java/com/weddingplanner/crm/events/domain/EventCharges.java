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

@AccumulationRegister(name="EventChargess",type=AccumulationType.TURNOVER,context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class EventCharges extends AccumulationRecord {
    @Dimension private Ref<EventProject> event;
    public Ref<EventProject> getEvent(){return event;}
    public void setEvent(Ref<EventProject> value){event=value;}
    @Dimension private Ref<BudgetArticle> article;
    public Ref<BudgetArticle> getArticle(){return article;}
    public void setArticle(Ref<BudgetArticle> value){article=value;}
    @Dimension private EventPhase phase;
    public EventPhase getPhase(){return phase;}
    public void setPhase(EventPhase value){phase=value;}
    @Dimension private InvoiceDirection direction;
    public InvoiceDirection getDirection(){return direction;}
    public void setDirection(InvoiceDirection value){direction=value;}
    @Dimension private ChargeKind kind;
    public ChargeKind getKind(){return kind;}
    public void setKind(ChargeKind value){kind=value;}
    @Resource(precision=15,scale=2) private BigDecimal amount;
    public BigDecimal getAmount(){return amount;}
    public void setAmount(BigDecimal value){amount=value;}
}
