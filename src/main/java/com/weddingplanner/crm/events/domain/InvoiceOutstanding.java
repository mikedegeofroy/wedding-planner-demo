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

@AccumulationRegister(name="InvoiceOutstandings",type=AccumulationType.BALANCE,context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class InvoiceOutstanding extends AccumulationRecord {
    @Dimension private Ref<EventInvoice> invoice;
    public Ref<EventInvoice> getInvoice(){return invoice;}
    public void setInvoice(Ref<EventInvoice> value){invoice=value;}
    @Resource(precision=15,scale=2) private BigDecimal amount;
    public BigDecimal getAmount(){return amount;}
    public void setAmount(BigDecimal value){amount=value;}
}
