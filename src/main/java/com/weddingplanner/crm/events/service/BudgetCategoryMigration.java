package com.weddingplanner.crm.events.service;
import org.springframework.stereotype.Component;
import su.onno.migration.*;
import com.weddingplanner.crm.events.domain.*;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
@Component public class BudgetCategoryMigration implements AppMigration {
 public String version(){return "2026.09.10.1";}
 public String description(){return "Preserve budget article categories as catalog references";}
 public static UUID id(String name){return UUID.nameUUIDFromBytes(("planner-budget-category:"+name).getBytes(StandardCharsets.UTF_8));}
 public void migrate(MigrationContext c){
  String articles=c.registry().getCatalogDescriptor(BudgetArticle.class).tableName();
  String categories=c.registry().getCatalogDescriptor(BudgetCategory.class).tableName();
  var names=c.handle().createQuery("SELECT DISTINCT category FROM "+articles+" WHERE category IS NOT NULL AND category <> ''").mapTo(String.class).list();
  for(String name:names){
   UUID id=id(name);
   if(c.handle().createQuery("SELECT COUNT(*) FROM "+categories+" WHERE _id = :id").bind("id",id).mapTo(Integer.class).one()==0)
    c.handle().createUpdate("INSERT INTO "+categories+" (_id,_code,_description,_deletion_mark,_version) VALUES (:id,:code,:name,FALSE,0)").bind("id",id).bind("code","BC-"+id.toString().substring(0,8)).bind("name",name).execute();
   c.handle().createUpdate("UPDATE "+articles+" SET budget_category = :id WHERE category = :name AND budget_category IS NULL").bind("id",id).bind("name",name).execute();
  }
 }
}
