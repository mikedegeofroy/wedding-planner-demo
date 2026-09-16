package com.weddingplanner.crm.events;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import su.onno.types.Ref;
import su.onno.posting.PostingService;
import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;
import com.weddingplanner.crm.events.service.EventBudgetReport;
import static org.assertj.core.api.Assertions.*;

/**
 * The detail behind the category totals, and the workbooks cut from it.
 *
 * <p>Its own database: these tests add events and estimates, and the finance suite asserts on the
 * exact set of seeded examples.
 */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:event-budget-report;DB_CLOSE_DELAY=-1","planner.events.demo=true","planner.marketing.demo-history=false", "planner.crm.demo-threads=false"})
class EventBudgetReportTest {
 @Autowired EventProjectRepository events;@Autowired EventBudgetRepository budgets;@Autowired BudgetArticleRepository articles;@Autowired EventInvoiceRepository invoices;@Autowired EventPaymentRepository payments;@Autowired ContactRepository contacts;@Autowired PostingService posting;
 @Autowired EventBudgetReport report;@Autowired com.weddingplanner.crm.events.web.EventWorkspaceController workspace;
 private static final org.springframework.security.core.Authentication MANAGER=
   new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("demo@weddingplanner.local","",
     List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MANAGER")));
 private EventPayment payment(EventInvoice i,String amount){var p=new EventPayment();p.setInvoice(Ref.of(EventInvoice.class,i.getId()));p.setAmount(new BigDecimal(amount));payments.save(p);return payments.findById(p.getId()).orElseThrow();}
 /** An event of this test's own, so asserting on a breakdown never disturbs the seeded examples. */
 private EventProject ownEvent(String name){
  var event=new EventProject();event.setDescription(name);events.save(event);
  return events.findById(event.getId()).orElseThrow();
 }
 private BudgetArticle ownArticle(String name){
  var article=new BudgetArticle();article.setDescription(name);article.setCategory("Production");
  articles.save(article);return articles.findById(article.getId()).orElseThrow();
 }
 private EventInvoice supplierInvoice(EventProject event,Contact contractor,BudgetArticle article,String amount){
  var invoice=new EventInvoice();invoice.setEvent(Ref.of(EventProject.class,event.getId()));
  invoice.setCounterparty(Ref.of(Contact.class,contractor.getId()));invoice.setDirection(InvoiceDirection.SUPPLIER);
  var item=new EventInvoiceLine();item.setArticle(Ref.of(BudgetArticle.class,article.getId()));
  item.setQuantity(BigDecimal.ONE);item.setUnitPrice(new BigDecimal(amount));invoice.getItems().add(item);
  invoices.save(invoice);var saved=invoices.findById(invoice.getId()).orElseThrow();posting.post(saved);
  return invoices.findById(invoice.getId()).orElseThrow();
 }
 /** A percentage hides the supplier; the breakdown has to name it, and the invoice behind it. */
 @Test void breakdownNamesEveryArticleItsContractorAndTheInvoicesRaisedAgainstIt(){
  var event=ownEvent("Breakdown detail");
  var article=ownArticle("Lighting design");
  var contractor=contacts.findAllActive().getFirst();
  var budget=new EventBudget();budget.setEvent(Ref.of(EventProject.class,event.getId()));budget.setScenario("Villa");
  var line=new EventBudgetLine();line.setArticle(Ref.of(BudgetArticle.class,article.getId()));
  line.setDetails("Respecting all musicians tech riders and venue rules");
  line.setContractor(Ref.of(Contact.class,contractor.getId()));line.setUnitPrice(new BigDecimal("40000"));
  budget.getItems().add(line);budgets.save(budget);

  var invoice=supplierInvoice(event,contractor,article,"1000");
  posting.post(payment(invoice,"400"));

  var breakdown=report.breakdown(event,budgets.findById(budget.getId()).orElseThrow());
  var found=breakdown.categories().stream().flatMap(c->c.lines().stream())
    .filter(l->article.getId().equals(l.articleId())&&l.budgeted()).findFirst().orElseThrow();
  assertThat(found.contractor()).isEqualTo(contractor.getDescription());
  assertThat(found.details()).isEqualTo("Respecting all musicians tech riders and venue rules");
  assertThat(found.amount()).isEqualByComparingTo("40000");
  assertThat(found.committed()).isEqualByComparingTo("1000");
  assertThat(found.paid()).isEqualByComparingTo("400");
  assertThat(found.commitments()).singleElement().satisfies(c->{
   assertThat(c.counterparty()).isEqualTo(contractor.getDescription());
   assertThat(c.number()).isEqualTo(invoice.getNumber());});
  // Every category total is exactly the lines under it — the screen and the detail cannot disagree.
  assertThat(breakdown.categories()).allSatisfy(c->assertThat(c.committed()).isEqualByComparingTo(
    c.lines().stream().map(EventBudgetReport.Line::committed).reduce(BigDecimal.ZERO,BigDecimal::add)));
 }
 /** Spend against an article the chosen scenario never carried is the finding, not a rounding error. */
 @Test void spendOutsideTheEstimateGetsItsOwnUnbudgetedLine(){
  var event=ownEvent("Unbudgeted spend");
  var budgeted=ownArticle("Ceremony flowers");
  var budget=new EventBudget();budget.setEvent(Ref.of(EventProject.class,event.getId()));budget.setScenario("Villa");
  var line=new EventBudgetLine();line.setArticle(Ref.of(BudgetArticle.class,budgeted.getId()));
  line.setUnitPrice(new BigDecimal("10000"));budget.getItems().add(line);budgets.save(budget);

  supplierInvoice(event,contacts.findAllActive().getFirst(),ownArticle("Helicopter transfer"),"7500");

  var unbudgeted=report.breakdown(event,budgets.findById(budget.getId()).orElseThrow()).categories().stream()
    .flatMap(c->c.lines().stream()).filter(l->!l.budgeted()).toList();
  assertThat(unbudgeted).singleElement().satisfies(l->{
   assertThat(l.article()).isEqualTo("Helicopter transfer");
   assertThat(l.amount()).isNull();
   assertThat(l.committed()).isEqualByComparingTo("7500");});
 }
 /** The workbook is a real .xlsx whose figures reconcile with the estimate it was cut from. */
 @Test void estimateWorkbookCarriesEveryArticleAndReconcilesToTheTotal()throws Exception{
  var budget=budgets.findAllActive().stream().max(Comparator.comparing(EventBudget::getTotal)).orElseThrow();
  var response=workspace.exportEstimate(budget.getId(),MANAGER);
  assertThat(response.getHeaders().getFirst("Content-Disposition")).contains(".xlsx");
  try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getBody()))){
   assertThat(book.getNumberOfSheets()).isEqualTo(2);
   var sheet=book.getSheetAt(0);
   var articleNames=new ArrayList<String>();BigDecimal footer=null;
   for(var row:sheet){
    var first=row.getCell(0);
    if(first!=null&&"Total".equals(first.getStringCellValue()))footer=BigDecimal.valueOf(row.getCell(9).getNumericCellValue());
    var article=row.getCell(1);
    if(article!=null&&article.getCellType()==org.apache.poi.ss.usermodel.CellType.STRING)articleNames.add(article.getStringCellValue());
   }
   assertThat(articleNames).contains(budget.getItems().stream()
     .map(l->articles.findById(l.getArticle().id()).orElseThrow().getDescription()).toList().toArray(new String[0]));
   assertThat(footer).isNotNull().isEqualByComparingTo(budget.getTotal());
  }
 }
 /** The comparison sheet is the PDF's own layout: a column per scenario, each footing to its total. */
 @Test void scenarioWorkbookPutsEveryScenarioInItsOwnColumn()throws Exception{
  var event=events.findAllActive().stream().filter(e->report.scenarios(e.getId()).size()>1).findFirst().orElseThrow();
  var scenarios=report.scenarios(event.getId());
  try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(
    workspace.exportScenarios(event.getId(),MANAGER).getBody()))){
   var sheet=book.getSheetAt(0);
   var header=sheet.getRow(3);
   for(int i=0;i<scenarios.size();i++)
    assertThat(header.getCell(5+i).getStringCellValue()).isEqualTo(EventBudgetReport.label(scenarios.get(i)));
   var total=java.util.stream.StreamSupport.stream(sheet.spliterator(),false)
     .filter(r->r.getCell(0)!=null&&r.getCell(0).getCellType()==org.apache.poi.ss.usermodel.CellType.STRING
       &&"Total".equals(r.getCell(0).getStringCellValue())).findFirst().orElseThrow();
   for(int i=0;i<scenarios.size();i++)
    assertThat(BigDecimal.valueOf(total.getCell(5+i).getNumericCellValue()))
      .isEqualByComparingTo(scenarios.get(i).getTotal());
  }
 }
}
