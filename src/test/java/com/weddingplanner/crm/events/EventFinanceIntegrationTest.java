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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:event-finance;DB_CLOSE_DELAY=-1","planner.events.demo=true","planner.marketing.demo-history=false", "planner.crm.demo-threads=false"})
class EventFinanceIntegrationTest {
 @Autowired org.jdbi.v3.core.Jdbi jdbi;
 @Autowired su.onno.metadata.MetadataRegistry registry;
 @Autowired BudgetCategoryRepository categories;
 @Autowired com.weddingplanner.crm.events.web.EventWorkspaceController workspace;
 @Autowired EventProjectRepository events;@Autowired EventBudgetRepository budgets;@Autowired BudgetArticleRepository articles;@Autowired EventInvoiceRepository invoices;@Autowired EventPaymentRepository payments;@Autowired InvoiceOutstandingRepository outstanding;@Autowired EventChargesRepository charges;@Autowired EventCashFlowRepository cash;@Autowired ContactRepository contacts;@Autowired PostingService posting;
 private EventInvoice invoice(InvoiceDirection direction){
  var i=new EventInvoice();i.setEvent(Ref.of(EventProject.class,events.findAllActive().getFirst().getId()));i.setCounterparty(Ref.of(Contact.class,contacts.findAllActive().getFirst().getId()));i.setDirection(direction);
  var l=new EventInvoiceLine();l.setArticle(Ref.of(BudgetArticle.class,articles.findAllActive().getFirst().getId()));l.setQuantity(new BigDecimal("2"));l.setUnitPrice(new BigDecimal("61"));i.getItems().add(l);invoices.save(i);return invoices.findById(i.getId()).orElseThrow();
 }
 private EventPayment payment(EventInvoice i,String amount){var p=new EventPayment();p.setInvoice(Ref.of(EventInvoice.class,i.getId()));p.setAmount(new BigDecimal(amount));payments.save(p);return payments.findById(p.getId()).orElseThrow();}
 private BigDecimal due(EventInvoice i){return outstanding.getBalance(Map.of("invoice",i.getId())).stream().map(InvoiceOutstanding::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add);}
 @Test void legacyCategoriesBecomeSharedReferencesWithoutLosingNames(){
  var a=new BudgetArticle();a.setDescription("Migration sample A");a.setCategory("Production");articles.save(a);
  var b=new BudgetArticle();b.setDescription("Migration sample B");b.setCategory("Production");articles.save(b);
  var migration=new com.weddingplanner.crm.events.service.BudgetCategoryMigration();
  jdbi.useTransaction(h->migration.migrate(new su.onno.migration.MigrationContext(h,registry,su.onno.schema.SqlDialect.H2)));
  jdbi.useTransaction(h->migration.migrate(new su.onno.migration.MigrationContext(h,registry,su.onno.schema.SqlDialect.H2)));
  var first=articles.findById(a.getId()).orElseThrow();var second=articles.findById(b.getId()).orElseThrow();
  assertThat(first.getBudgetCategory()).isNotNull().isEqualTo(second.getBudgetCategory());
  assertThat(first.getCategory()).isEqualTo("Production");
  assertThat(categories.findById(first.getBudgetCategory().id()).orElseThrow().getDescription()).isEqualTo("Production");
 }
 /**
  * The imported PDFs, which are the only estimates in the demo carrying amounts somebody actually
  * quoted. The book of couple-named weddings is seeded beside them and re-prices copies of these,
  * so both halves are identified by their source file rather than by counting every row.
  */
 @Test void importsFiveScenariosWithoutInventingActuals(){
  var imported=budgets.findAllActive().stream().filter(b->b.getSourceFile()!=null&&!b.getSourceFile().isBlank()).toList();
  assertThat(imported).hasSize(5);assertThat(imported.stream().map(EventBudget::getTotal)).usingComparatorForType(BigDecimal::compareTo,BigDecimal.class).containsExactlyInAnyOrder(new BigDecimal("736600"),new BigDecimal("461600"),new BigDecimal("751395"),new BigDecimal("767520"),new BigDecimal("1035825"));
  var examples=imported.stream().map(b->events.findActiveById(b.getEvent().id()).orElseThrow()).distinct().toList();
  assertThat(examples).isNotEmpty().allMatch(e->e.getClient()==null&&e.getStartDate()==null);
  var example=imported.stream().filter(b->b.getRefundableDeposit().signum()>0).findFirst().orElseThrow();assertThat(example.getRefundableDeposit()).isEqualByComparingTo("35000");assertThat(example.getUnknownItems()).isPositive();assertThat(example.getItems().stream().filter(l->l.getPriceState()==BudgetPriceState.TBD)).allMatch(l->l.getAmount()==null);
 }
 @Test void partialPaymentOverpaymentAndUnpostingAreAtomic(){
  var i=invoice(InvoiceDirection.SUPPLIER);assertThat(i.getTotal()).isEqualByComparingTo("122");assertThat(charges.getRecordsByDocument(i.getId())).isEmpty();assertThat(due(i)).isZero();posting.post(i);assertThat(due(i)).isEqualByComparingTo("122");
  var p=payment(i,"50");posting.post(p);assertThat(due(i)).isEqualByComparingTo("72");assertThat(cash.getRecordsByDocument(p.getId())).singleElement().satisfies(r->assertThat(r.getDirection()).isEqualTo(InvoiceDirection.SUPPLIER));
  var tooMuch=payment(i,"73");assertThatThrownBy(()->posting.post(tooMuch)).isInstanceOf(RuntimeException.class);assertThat(payments.findById(tooMuch.getId()).orElseThrow().isPosted()).isFalse();assertThat(cash.getRecordsByDocument(tooMuch.getId())).isEmpty();assertThat(due(i)).isEqualByComparingTo("72");
  assertThatThrownBy(()->posting.unpost(invoices.findById(i.getId()).orElseThrow())).isInstanceOf(RuntimeException.class);assertThat(invoices.findById(i.getId()).orElseThrow().isPosted()).isTrue();
  posting.unpost(payments.findById(p.getId()).orElseThrow());assertThat(due(i)).isEqualByComparingTo("122");posting.unpost(invoices.findById(i.getId()).orElseThrow());assertThat(due(i)).isZero();
 }
 @Test void clientReceiptsCarryDirectionAndPaidInvoiceCannotMoveEvent(){
  var i=invoice(InvoiceDirection.CLIENT);posting.post(i);var p=payment(i,"122");posting.post(p);assertThat(due(i)).isZero();assertThat(cash.getRecordsByDocument(p.getId())).singleElement().satisfies(r->assertThat(r.getDirection()).isEqualTo(InvoiceDirection.CLIENT));
  var changed=invoices.findById(i.getId()).orElseThrow();changed.setDirection(InvoiceDirection.SUPPLIER);assertThatThrownBy(()->invoices.save(changed)).isInstanceOf(RuntimeException.class);
 }
 @Test void budgetRowCreatesOnlyADraftWithCorrectEventArticleAndContractor(){
  var manager=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("demo@weddingplanner.local","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MANAGER")));
  // The crew seeder already owns the articles it recognises, so the unowned line has to be sought
  // out — committing to a supplier is exactly what must fail while nobody owns the article.
  java.util.function.Predicate<EventBudgetLine> unowned=l->l.getContractor()==null&&l.getAmount()!=null&&l.getAmount().signum()>0;
  var budget=budgets.findAllActive().stream().filter(b->b.getItems().stream().anyMatch(unowned)).findFirst().orElseThrow();
  var line=budget.getItems().stream().filter(unowned).findFirst().orElseThrow();
  // The demo crew already owns the articles that are obviously theirs, and this case is about an
  // article nobody owns yet, so the row is explicitly unassigned rather than assumed to be.
  line.setContractor(null);budgets.save(budget);
  assertThatThrownBy(()->workspace.expense(budget.getId(),line.getId(),manager)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  line.setContractor(Ref.of(Contact.class,contacts.findAllActive().getFirst().getId()));budgets.save(budget);
  UUID invoiceId=(UUID)workspace.expense(budget.getId(),line.getId(),manager).get("id");var i=invoices.findById(invoiceId).orElseThrow();
  assertThat(i.isPosted()).isFalse();assertThat(i.getEvent()).isEqualTo(budget.getEvent());assertThat(i.getCounterparty()).isEqualTo(line.getContractor());assertThat(i.getItems().getFirst().getArticle()).isEqualTo(line.getArticle());assertThat(i.getTotal()).isEqualByComparingTo(line.getAmount());assertThat(charges.getRecordsByDocument(invoiceId)).isEmpty();
  assertThatThrownBy(()->workspace.workspace(budget.getEvent().id(),null,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  var other=events.findAllActive().stream().filter(e->!e.getId().equals(budget.getEvent().id())).findFirst().orElseThrow();
  assertThatThrownBy(()->workspace.select(other.getId(),new com.weddingplanner.crm.events.web.EventWorkspaceController.Selection(budget.getId()),manager)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void cannotRecordPaymentAgainstDraftInvoice(){var i=invoice(InvoiceDirection.CLIENT);assertThatThrownBy(()->payment(i,"1")).isInstanceOf(RuntimeException.class);}
}
