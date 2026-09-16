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

@Document(name="EventBudgets",title="Estimates",numberPrefix="EST-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class EventBudget extends DocumentObject implements BeforeWriteHandler, Validated {
    @Attribute(displayName="Event",required=true) private Ref<EventProject> event;
    @Attribute(displayName="Scenario / venue",required=true) private String scenario;
    @Attribute(displayName="Version",min=1) private Integer revision = 1;
    @Attribute(displayName="Price basis",length=300) private String priceBasis = "Amounts as quoted; VAT to confirm";
    @Attribute(displayName="Known subtotal (EUR)",precision=15,scale=2) private BigDecimal total = BigDecimal.ZERO;
    /** What the same priced rows cost Wedding Planner. Pass-through rows carry their quoted price into it. */
    @Attribute(displayName="Our cost (EUR)",precision=15,scale=2) private BigDecimal costTotal = BigDecimal.ZERO;
    @Attribute(displayName="Unpriced items") private Integer unknownItems = 0;
    @Attribute(displayName="Refundable deposit (outside budget)",precision=15,scale=2) private BigDecimal refundableDeposit = BigDecimal.ZERO;
    @Attribute(displayName="Source example",length=300) private String sourceFile;
    public Ref<EventProject> getEvent() { return event; }
    public void setEvent(Ref<EventProject> value) { event=value; }
    public String getScenario() { return scenario; }
    public void setScenario(String value) { scenario=value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision=value; }
    public String getPriceBasis() { return priceBasis; }
    public void setPriceBasis(String value) { priceBasis=value; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal value) { total=value; }
    public BigDecimal getCostTotal() { return costTotal; }
    public void setCostTotal(BigDecimal value) { costTotal=value; }
    public Integer getUnknownItems() { return unknownItems; }
    public void setUnknownItems(Integer value) { unknownItems=value; }
    public BigDecimal getRefundableDeposit() { return refundableDeposit; }
    public void setRefundableDeposit(BigDecimal value) { refundableDeposit=value; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String value) { sourceFile=value; }

    @TabularSection(name="items") private List<EventBudgetLine> items=new ArrayList<>();
    public List<EventBudgetLine> getItems(){return items;}
    public void setItems(List<EventBudgetLine> value){items=value;}
    @Override public void beforeWrite(){
        total=BigDecimal.ZERO;costTotal=BigDecimal.ZERO;unknownItems=0;
        if(items==null)items=new ArrayList<>();
        for(var line:items){
            if(line.getPriceState()==BudgetPriceState.QUOTED && line.getUnitPrice()!=null && line.getQuantity()!=null){
                line.setAmount(line.getQuantity().multiply(line.getUnitPrice()).setScale(2,RoundingMode.HALF_UP));total=total.add(line.getAmount());
                line.setCostAmount(cost(line));costTotal=costTotal.add(line.getCostAmount());
            } else {line.setAmount(null);line.setCostAmount(null);if(line.getPriceState()==BudgetPriceState.TBD || line.getPriceState()==BudgetPriceState.QUOTED)unknownItems++;}
        }
    }
    /**
     * What a priced row costs Wedding Planner. An explicit cost wins; Wedding Planner's own fee has no supplier behind
     * it and costs nothing; anything else is assumed to be passed through at the price the couple
     * was quoted, so a blank cost column reports no margin rather than inventing one.
     */
    private static BigDecimal cost(EventBudgetLine line){
        if(line.getUnitCost()!=null)return line.getQuantity().multiply(line.getUnitCost()).setScale(2,RoundingMode.HALF_UP);
        return line.getKind()==ChargeKind.AGENCY_FEE?BigDecimal.ZERO:line.getAmount();
    }
    @Override public List<BusinessRule> rules(){return List.of(
        new BusinessRule("event","Choose an active event",()->EventRules.activeEvent(event)),
        new BusinessRule("rows","Add budget articles with valid, nonnegative quantities and prices",()->items!=null&&!items.isEmpty()&&items.stream().allMatch(l->EventRules.activeArticle(l.getArticle())&&l.getPriceState()!=null&&(l.getQuantity()==null||l.getQuantity().signum()>0)&&(l.getUnitPrice()==null||l.getUnitPrice().signum()>=0)&&(l.getUnitCost()==null||l.getUnitCost().signum()>=0))),
        new BusinessRule("deposit","Deposit must not be negative",()->refundableDeposit!=null&&refundableDeposit.signum()>=0));}

}
