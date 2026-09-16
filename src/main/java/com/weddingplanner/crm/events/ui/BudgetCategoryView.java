package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import com.weddingplanner.crm.events.domain.BudgetCategory;
@Component public class BudgetCategoryView implements EntityView<BudgetCategory>{
 public Class<BudgetCategory> entity(){return BudgetCategory.class;}
 public void list(ListSpec<BudgetCategory> l){l.columns(BudgetCategory::getDescription);}
 public void fields(EntityConfigBuilder<BudgetCategory> f){f.field(BudgetCategory::getDescription).label("Category");}
}
