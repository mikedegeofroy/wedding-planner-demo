package com.weddingplanner.crm.events.web;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import su.onno.ui.UiAccessService;
import su.onno.types.Ref;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;
import com.weddingplanner.crm.events.service.EventBudgetReport;
import com.weddingplanner.crm.events.service.EventMarginService;
import com.weddingplanner.crm.events.service.EventBudgetWorkbook;

@RestController
@RequestMapping("/api/planner/events")
public class EventWorkspaceController {
 private final EventProjectRepository events;private final EventBudgetRepository budgets;private final EventInvoiceRepository invoices;private final EventPaymentRepository payments;private final BudgetArticleRepository articles;private final ContactRepository contacts;private final EventPartyRepository parties;private final BudgetCategoryRepository categories;private final EventBudgetReport report;private final EventBudgetWorkbook workbooks;private final EventMarginService margins;private final UiAccessService access;private final boolean readOnly;private final List<String> marginRoles;
 public EventWorkspaceController(EventProjectRepository events,EventBudgetRepository budgets,EventInvoiceRepository invoices,EventPaymentRepository payments,BudgetArticleRepository articles,ContactRepository contacts,EventPartyRepository parties,BudgetCategoryRepository categories,EventBudgetReport report,EventBudgetWorkbook workbooks,EventMarginService margins,UiAccessService access,@Value("${onno.ui.read-only:false}")boolean readOnly,@Value("${planner.events.margin-roles:MANAGER,ADMIN}")List<String> marginRoles){this.events=events;this.budgets=budgets;this.invoices=invoices;this.payments=payments;this.articles=articles;this.contacts=contacts;this.parties=parties;this.categories=categories;this.report=report;this.workbooks=workbooks;this.margins=margins;this.access=access;this.readOnly=readOnly;this.marginRoles=marginRoles;}
 private void read(Principal p){if(p==null||!access.canRead(p,"catalog","EventProjects")||!access.canRead(p,"document","EventBudgets")||!access.canRead(p,"document","EventInvoices")||!access.canRead(p,"document","EventPayments")||!access.canRead(p,"catalog","Contacts")||!access.canRead(p,"catalog","BudgetArticles"))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 private void write(Principal p,String type,String name){read(p);if(readOnly||!access.canWrite(p,type,name))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 /**
  * What an event earns is the one figure on this screen that is never shown to a couple, so it is
  * gated on roles of its own rather than riding along with the rest of the workspace. Narrow
  * {@code planner.events.margin-roles} to keep it away from anyone the app is later opened up to.
  */
 private boolean canSeeMargin(Principal p){return marginRoles!=null&&!marginRoles.isEmpty()&&access.hasAnyRole(p,marginRoles);}
 private static boolean same(Ref<EventProject> ref,UUID id){return ref!=null&&id!=null&&id.equals(ref.id());}
 private String contact(Ref<Contact> ref){return ref==null?"Unassigned":contacts.findById(ref.id()).map(Contact::getDescription).orElse("Unavailable contact");}
 private String article(Ref<BudgetArticle> ref){return ref==null?"—":articles.findById(ref.id()).map(BudgetArticle::getDescription).orElse("Unavailable article");}
 private static Map<String,Object> row(Object... pairs){var out=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
 @GetMapping("/{id}/workspace") @Transactional(readOnly=true)
 public Map<String,Object> workspace(@PathVariable UUID id,@RequestParam(required=false) UUID budget,Principal p){
  read(p);var event=events.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  var inv=invoices.findAllActive().stream().filter(i->same(i.getEvent(),id)).toList();
  var pay=payments.findAllActive().stream().filter(i->same(i.getEvent(),id)).toList();
  var budgetRows=budgets.findAllActive().stream().filter(b->same(b.getEvent(),id)).map(b->row("id",b.getId(),"number",b.getNumber(),"scenario",b.getScenario(),"revision",b.getRevision(),"total",b.getTotal(),"unknown",b.getUnknownItems(),"deposit",b.getRefundableDeposit(),"priceBasis",b.getPriceBasis(),"source",b.getSourceFile(),"items",b.getItems().stream().map(l->row("id",l.getId(),"article",article(l.getArticle()),"phase",l.getPhase(),"details",l.getDetails(),"contractor",contact(l.getContractor()),"contractorId",l.getContractor()==null?null:l.getContractor().id(),"state",l.getPriceState(),"amount",l.getAmount())).toList())).toList();
  var invoiceRows=inv.stream().map(i->{var paid=pay.stream().filter(v->v.isPosted()&&v.getInvoice()!=null&&v.getInvoice().id().equals(i.getId())).map(EventPayment::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);return row("id",i.getId(),"number",i.getNumber(),"counterparty",contact(i.getCounterparty()),"direction",i.getDirection(),"total",i.getTotal(),"posted",i.isPosted(),"paid",paid,"due",i.isPosted()?i.getTotal().subtract(paid):null);}).toList();
  // The event's own facts travel with the workspace so the event page can head itself without a
  // second round trip — it is a page in its own right now, not a panel under the record form.
  var crew=parties.findAllActive().stream().filter(party->same(party.getEvent(),id))
    .map(party->row("id",party.getId(),"contact",contact(party.getContact()),
      "contactId",party.getContact()==null?null:party.getContact().id(),
      "role",party.getRole(),"scope",party.getScope())).toList();
  // Where the money actually goes, article by article: what the estimate quotes, who was committed
  // to it, and how much of that is paid. The breakdown follows the scenario the user is reading —
  // "budget" — rather than only the one driving the totals, so alternatives can be opened up too.
  var reading=budget==null?null:budgets.findActiveById(budget).filter(b->same(b.getEvent(),id)).orElse(null);
  if(reading==null&&event.getSelectedBudget()!=null)
   reading=budgets.findActiveById(event.getSelectedBudget().id()).orElse(null);
  var breakdown=report.breakdown(event,reading);
  var categoryRows=breakdown.categories();
  var header=row("id",event.getId(),"name",event.getDescription(),"code",event.getCode(),
    "client",contact(event.getClient()),"clientId",event.getClient()==null?null:event.getClient().id(),
    "stage",event.getStage(),"startDate",event.getStartDate(),"endDate",event.getEndDate(),
    "location",event.getLocation(),"guests",event.getGuests(),"currency",event.getCurrency(),
    "notes",event.getNotes(),"crew",crew);
  return row("event",header,"categories",categoryRows,"breakdownOf",breakdown.budget()==null?null:breakdown.budget().getId(),"selectedBudget",event.getSelectedBudget()==null?null:event.getSelectedBudget().id(),"canSeeMargin",canSeeMargin(p),"canWrite",!readOnly&&access.canWrite(p,"catalog","EventProjects")&&access.canWrite(p,"document","EventInvoices")&&access.canWrite(p,"document","EventPayments"),"budgets",budgetRows,"invoices",invoiceRows,"payments",pay.stream().map(v->row("id",v.getId(),"number",v.getNumber(),"date",v.getDate(),"direction",v.getDirection(),"amount",v.getAmount(),"posted",v.isPosted(),"reference",v.getReference())).toList(),"clientBilled",sumInvoices(inv,InvoiceDirection.CLIENT),"supplierBilled",sumInvoices(inv,InvoiceDirection.SUPPLIER),"received",sumPayments(pay,InvoiceDirection.CLIENT),"paid",sumPayments(pay,InvoiceDirection.SUPPLIER));
 }
 private BigDecimal sumInvoices(List<EventInvoice> list,InvoiceDirection d){return list.stream().filter(i->i.isPosted()&&i.getDirection()==d).map(EventInvoice::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add);}
 private BigDecimal sumPayments(List<EventPayment> list,InvoiceDirection d){return list.stream().filter(i->i.isPosted()&&i.getDirection()==d).map(EventPayment::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);}
 /**
  * The internal view of an event: Wedding Planner's fee, the markup on supplier articles and the commissions
  * suppliers rebate, forecast from the chosen estimate and booked from posted invoices.
  */
 @GetMapping("/{id}/margin") @Transactional(readOnly=true)
 public EventMarginService.Margin margin(@PathVariable UUID id,Principal p){
  read(p);if(!canSeeMargin(p))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(events.findActiveById(id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  return margins.margin(id);
 }
 public record Selection(UUID budget){}
 @PostMapping("/{id}/budget") @Transactional
 public Map<String,Object> select(@PathVariable UUID id,@RequestBody Selection choice,Principal p){write(p,"catalog","EventProjects");var event=events.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));var budget=budgets.findActiveById(choice.budget()).filter(b->same(b.getEvent(),id)).orElseThrow(()->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose this event's estimate"));event.setSelectedBudget(Ref.of(EventBudget.class,budget.getId()));events.save(event);return row("id",id);}
 /**
  * Copy an estimate as the next version of the same scenario. A revision is how a quote moves —
  * the venue re-prices, the couple cuts the guest list — so the original stays as it was sent and
  * the copy carries the same lines to edit. Posted state is never copied: a version starts a draft.
  */
 @PostMapping("/budgets/{id}/copy") @Transactional
 public Map<String,Object> copy(@PathVariable UUID id,Principal p){
  write(p,"document","EventBudgets");
  var source=budgets.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  var copy=new EventBudget();
  copy.setEvent(source.getEvent());copy.setScenario(source.getScenario());
  copy.setPriceBasis(source.getPriceBasis());copy.setRefundableDeposit(source.getRefundableDeposit());
  copy.setSourceFile(source.getSourceFile());
  // The next version of THIS scenario, not of the event: two venues are quoted side by side.
  copy.setRevision(budgets.findAllActive().stream()
    .filter(b->same(b.getEvent(),source.getEvent()==null?null:source.getEvent().id())
      && Objects.equals(b.getScenario(),source.getScenario()))
    .map(b->b.getRevision()==null?1:b.getRevision()).max(Integer::compareTo).orElse(1)+1);
  for(var line:source.getItems()){
   var item=new EventBudgetLine();
   item.setPhase(line.getPhase());item.setArticle(line.getArticle());item.setDetails(line.getDetails());
   item.setContractor(line.getContractor());item.setPriceState(line.getPriceState());
   item.setQuantity(line.getQuantity());item.setUnitPrice(line.getUnitPrice());
   copy.getItems().add(item);
  }
  budgets.save(copy);
  return row("id",copy.getId(),"revision",copy.getRevision());
 }
 /**
  * The estimate as a workbook: every article with its comments, its contractor, what has been
  * committed against it and what is paid, plus the category summary the event page shows. Estimates
  * are read, argued over and countersigned in a spreadsheet, so the export is a real .xlsx rather
  * than a CSV that loses the money formats and the column widths.
  */
 @GetMapping("/budgets/{id}/export") @Transactional(readOnly=true)
 public ResponseEntity<byte[]> exportEstimate(@PathVariable UUID id,Principal p)throws java.io.IOException{
  read(p);
  var budget=budgets.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  var event=budget.getEvent()==null?null:events.findActiveById(budget.getEvent().id()).orElse(null);
  if(event==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"This estimate has no active event");
  return workbook(workbooks.estimate(event,budget),
    EventBudgetReport.fileName(event,EventBudgetReport.label(budget)));
 }
 /**
  * Every scenario of an event as a grid: a row per quoted position, a column per estimate. The same
  * {@link EventBudgetReport#comparison} the workbook is written from, so the page a planner argues
  * over and the sheet they send the couple cannot drift apart — two venues quoting the same position
  * line up on one row in both.
  */
 @GetMapping("/{id}/scenarios") @Transactional(readOnly=true)
 public Map<String,Object> scenarios(@PathVariable UUID id,Principal p){
  read(p);
  var event=events.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  var comparison=report.comparison(event);
  var selected=event.getSelectedBudget()==null?null:event.getSelectedBudget().id();
  var columns=comparison.scenarios().stream().map(s->row("id",s.budget().getId(),
    "label",EventBudgetReport.label(s.budget()),"scenario",s.budget().getScenario(),
    "revision",s.budget().getRevision(),"number",s.budget().getNumber(),"total",s.total(),
    "unknown",s.budget().getUnknownItems(),"deposit",s.budget().getRefundableDeposit(),
    "priceBasis",s.budget().getPriceBasis(),
    "selected",s.budget().getId().equals(selected))).toList();
  // amounts/states are positional against the columns; a null is a position this estimate never
  // quoted, which is a different fact from a position it quoted at zero.
  var rows=comparison.rows().stream().map(r->row("category",r.category(),"article",r.article(),
    "details",r.details(),"phase",r.phaseLabel(),"kind",r.kindLabel(),
    "amounts",r.amounts(),"states",r.states())).toList();
  return row("scenarios",columns,"rows",rows,"commentsDiffer",comparison.commentsDiffer());
 }
 /** Every scenario of an event on one grid — the layout the venue quotes arrive in. */
 @GetMapping("/{id}/scenarios/export") @Transactional(readOnly=true)
 public ResponseEntity<byte[]> exportScenarios(@PathVariable UUID id,Principal p)throws java.io.IOException{
  read(p);
  var event=events.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  if(report.scenarios(id).isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This event has no estimates to compare");
  return workbook(workbooks.comparison(event),EventBudgetReport.fileName(event,"estimate scenarios"));
 }
 /** RFC 5987 filename* as well as the plain one — the event names carry accents and en dashes. */
 private static ResponseEntity<byte[]> workbook(byte[] bytes,String fileName){
  var ascii=fileName.replaceAll("[^A-Za-z0-9 ._-]","_");
  var encoded=java.net.URLEncoder.encode(fileName,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
  return ResponseEntity.ok()
    .header("Content-Type",EventBudgetWorkbook.CONTENT_TYPE)
    .header("Content-Disposition","attachment; filename=\""+ascii+"\"; filename*=UTF-8''"+encoded)
    .header("Cache-Control","no-store")
    .body(bytes);
 }
 @PostMapping("/budgets/{id}/lines/{lineId}/invoice") @Transactional
 public Map<String,Object> expense(@PathVariable UUID id,@PathVariable UUID lineId,Principal p){
  write(p,"document","EventInvoices");var budget=budgets.findActiveById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  var line=budget.getItems().stream().filter(l->lineId.equals(l.getId())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  if(line.getContractor()==null||line.getPriceState()!=BudgetPriceState.QUOTED||line.getAmount()==null||line.getAmount().signum()<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Assign a contractor and a positive quoted amount in the estimate first");
  var invoice=new EventInvoice();invoice.setEvent(budget.getEvent());invoice.setCounterparty(line.getContractor());invoice.setNotes("Prepared from estimate "+budget.getNumber()+" / "+budget.getScenario()+". Review against the contractor's invoice before posting.");
  var item=new EventInvoiceLine();item.setArticle(line.getArticle());item.setPhase(line.getPhase());item.setKind(line.getKind());item.setDetails(line.getDetails()==null?null:line.getDetails().substring(0,Math.min(3000,line.getDetails().length())));item.setQuantity(line.getQuantity());item.setUnitPrice(line.getUnitPrice());invoice.getItems().add(item);invoices.save(invoice);return row("id",invoice.getId());
 }
}
