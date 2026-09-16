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

@Document(name="EventPayments",title="Payments",numberPrefix="PAY-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class EventPayment extends DocumentObject implements BeforeWriteHandler, Validated, Postable {
    @Attribute(displayName="Invoice",required=true) private Ref<EventInvoice> invoice;
    @Attribute(displayName="Event") private Ref<EventProject> event;
    @Attribute(displayName="Invoice direction") private InvoiceDirection direction;
    @Attribute(displayName="Payment (EUR)",precision=15,scale=2) private BigDecimal amount = BigDecimal.ZERO;
    @Attribute(displayName="Bank reference") private String reference;
    @Attribute(displayName="Notes",length=2000) private String notes;
    public Ref<EventInvoice> getInvoice() { return invoice; }
    public void setInvoice(Ref<EventInvoice> value) { invoice=value; }
    public Ref<EventProject> getEvent() { return event; }
    public void setEvent(Ref<EventProject> value) { event=value; }
    public InvoiceDirection getDirection() { return direction; }
    public void setDirection(InvoiceDirection value) { direction=value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount=value; }
    public String getReference() { return reference; }
    public void setReference(String value) { reference=value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes=value; }

    @Override public void beforeWrite(){var linked=EventRules.invoice(invoice);if(linked!=null){event=linked.getEvent();direction=linked.getDirection();}}
    @Override public List<BusinessRule> rules(){return List.of(new BusinessRule("invoice","Choose a posted, active invoice",()->EventRules.invoicePosted(invoice)),new BusinessRule("amount","Payment must be positive",()->amount!=null&&amount.signum()>0));}
    @Override public void handlePosting(PostingContext context){
        context.movements(InvoiceOutstanding.class).addExpense(r->{r.setInvoice(invoice);r.setAmount(amount);});
        context.movements(EventCashFlow.class).addReceipt(r->{r.setEvent(event);r.setDirection(direction);r.setAmount(amount);});
    }

}
