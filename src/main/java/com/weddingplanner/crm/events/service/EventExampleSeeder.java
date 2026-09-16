package com.weddingplanner.crm.events.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import su.onno.types.Ref;
import com.weddingplanner.crm.events.domain.*;
import com.weddingplanner.crm.events.repository.*;

/** Opt-in, idempotent import of the supplied estimates; never creates financial transactions. */
@Component
@ConditionalOnProperty(name="planner.events.demo",havingValue="true")
@org.springframework.core.annotation.Order(20)
public class EventExampleSeeder implements ApplicationRunner {
 private final EventProjectRepository events; private final EventBudgetRepository budgets; private final BudgetArticleRepository articles; private final ObjectMapper json; private final BudgetCategoryRepository categories;
 public EventExampleSeeder(EventProjectRepository events,EventBudgetRepository budgets,BudgetArticleRepository articles,ObjectMapper json,BudgetCategoryRepository categories){this.events=events;this.budgets=budgets;this.articles=articles;this.json=json;this.categories=categories;}
 private UUID id(String key){return UUID.nameUUIDFromBytes(("planner-event-example:"+key).getBytes(StandardCharsets.UTF_8));}
 /**
  * Wedding Planner's own planning fee, told apart from the suppliers it sits among. The source estimates name
  * it outright — a planner's fee, organisation and coordination of a day — so this classifies rows
  * that are already there rather than adding any. Getting it wrong in either direction misstates
  * what the event earns: a fee counted as a supplier article reports cost Wedding Planner never pays, and a
  * supplier counted as a fee reports margin Wedding Planner never makes.
  */
 static ChargeKind kind(String article){
  String name=article==null?"":article.toLowerCase(java.util.Locale.ROOT).replace('\u2019','\'');
  boolean fee=name.contains("planner's fee")||name.contains("organization and coordination")
    ||name.contains("planning & coordinating")||name.contains("planning and coordinating");
  return fee?ChargeKind.AGENCY_FEE:ChargeKind.SERVICE;
 }
 @Override @Transactional public void run(ApplicationArguments args)throws Exception{
  try(var stream=getClass().getResourceAsStream("/events/budget-examples.json")){
   for(var e:json.readTree(stream)){
    String key=e.path("key").asText();UUID eventId=id(key);
    if(events.findById(eventId).isPresent())continue;
    var event=new EventProject();event.setId(eventId);event.setDescription(e.path("name").asText());event.setGuests(e.path("guests").asInt());event.setLocation("Lake Como, Italy");event.setNotes("Imported estimate example. Client and event dates have not been assigned. Source: "+e.path("source").asText());events.save(event);
    for(var b:e.path("budgets")){
     var budget=new EventBudget();budget.setId(id(key+":"+b.path("scenario").asText()));budget.setEvent(Ref.of(EventProject.class,eventId));budget.setScenario(b.path("scenario").asText());budget.setPriceBasis(b.path("priceBasis").asText());budget.setRefundableDeposit(b.path("deposit").decimalValue());budget.setSourceFile(e.path("source").asText());
     for(var row:b.path("items")){
      String label=row.path("article").asText();UUID articleId=id("article:"+label.toLowerCase());
      if(articles.findById(articleId).isEmpty()){var a=new BudgetArticle();a.setId(articleId);a.setDescription(label);String category=row.path("phase").asText();UUID categoryId=BudgetCategoryMigration.id(category);if(categories.findById(categoryId).isEmpty()){var c=new BudgetCategory();c.setId(categoryId);c.setDescription(category);categories.save(c);}a.setBudgetCategory(Ref.of(BudgetCategory.class,categoryId));articles.save(a);}
      var line=new EventBudgetLine();line.setArticle(Ref.of(BudgetArticle.class,articleId));line.setPhase(EventPhase.valueOf(row.path("phase").asText()));line.setDetails(row.path("details").asText());line.setKind(kind(label));line.setPriceState(BudgetPriceState.valueOf(row.path("priceState").asText()));line.setUnitPrice(row.path("price").isNull()?null:row.path("price").decimalValue());budget.getItems().add(line);
     }
     budgets.save(budget);
     if(budget.getTotal().compareTo(b.path("expectedTotal").decimalValue())!=0)throw new IllegalStateException("Imported estimate total does not reconcile");
    }
   }
  }
 }
}
