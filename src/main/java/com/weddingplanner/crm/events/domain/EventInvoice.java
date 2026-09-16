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

@Document(name="EventInvoices",title="Invoices",numberPrefix="INV-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class EventInvoice extends DocumentObject implements BeforeWriteHandler, Validated, Postable {
    @Attribute(displayName="Event",required=true) private Ref<EventProject> event;
    @Attribute(displayName="Client / contractor",required=true) private Ref<Contact> counterparty;
    @Attribute(displayName="Direction",required=true) private InvoiceDirection direction = InvoiceDirection.SUPPLIER;
    @Attribute(displayName="Counterparty invoice #") private String externalNumber;
    @Attribute(displayName="Due date") private LocalDate dueDate;
    @Attribute(displayName="Total incl. VAT (EUR)",precision=15,scale=2) private BigDecimal total = BigDecimal.ZERO;
    @Attribute(displayName="Notes",length=3000) private String notes;
    public Ref<EventProject> getEvent() { return event; }
    public void setEvent(Ref<EventProject> value) { event=value; }
    public Ref<Contact> getCounterparty() { return counterparty; }
    public void setCounterparty(Ref<Contact> value) { counterparty=value; }
    public InvoiceDirection getDirection() { return direction; }
    public void setDirection(InvoiceDirection value) { direction=value; }
    public String getExternalNumber() { return externalNumber; }
    public void setExternalNumber(String value) { externalNumber=value; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate value) { dueDate=value; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal value) { total=value; }
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes=value; }

    @TabularSection(name="items") private List<EventInvoiceLine> items=new ArrayList<>();
    public List<EventInvoiceLine> getItems(){return items;}
    public void setItems(List<EventInvoiceLine> value){items=value;}
    @Override public void beforeWrite(){total=BigDecimal.ZERO;if(items==null)items=new ArrayList<>();for(var line:items){
        line.setAmount(line.getQuantity()==null||line.getUnitPrice()==null?BigDecimal.ZERO:line.getQuantity().multiply(line.getUnitPrice()).setScale(2,RoundingMode.HALF_UP));total=total.add(line.getAmount());}}
    @Override public List<BusinessRule> rules(){return List.of(
        new BusinessRule("event","Choose an active event and counterparty",()->EventRules.activeEvent(event)&&EventRules.activeContact(counterparty)),
        new BusinessRule("party","Unpost the invoice before changing its event, direction or counterparty",()->EventRules.stableInvoiceParty(this)),
        new BusinessRule("rows","Add positive invoice lines with a budget article",()->items!=null&&!items.isEmpty()&&items.stream().allMatch(l->EventRules.activeArticle(l.getArticle())&&l.getPhase()!=null&&l.getKind()!=null&&l.getQuantity()!=null&&l.getQuantity().signum()>0&&l.getUnitPrice()!=null&&l.getUnitPrice().signum()>=0)),
        new BusinessRule("total","Invoice total must be positive",()->total.signum()>0));}
    @Override public void handlePosting(PostingContext context){
        context.movements(InvoiceOutstanding.class).addReceipt(r->{r.setInvoice(Ref.of(EventInvoice.class,getId()));r.setAmount(total);});
        for(var line:items)context.movements(EventCharges.class).addReceipt(r->{r.setEvent(event);r.setArticle(line.getArticle());r.setPhase(line.getPhase());r.setDirection(direction);r.setKind(line.getKind());r.setAmount(line.getAmount());});
    }

}
