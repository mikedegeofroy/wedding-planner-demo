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

@Catalog(name="BudgetArticles",title="Budget articles",codePrefix="BA-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class BudgetArticle extends CatalogObject {
    // Retained for migration/audit; new edits use the catalog reference.
    @Attribute(displayName="Legacy category") private String category;
    @Attribute(displayName="Category") private Ref<BudgetCategory> budgetCategory;
    public Ref<BudgetCategory> getBudgetCategory(){return budgetCategory;}
    public void setBudgetCategory(Ref<BudgetCategory> value){budgetCategory=value;}
    public String getCategory() { return category; }
    public void setCategory(String value) { category=value; }

}
