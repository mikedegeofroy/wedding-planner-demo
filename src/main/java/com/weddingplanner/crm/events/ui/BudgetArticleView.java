package com.weddingplanner.crm.events.ui;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
import com.weddingplanner.crm.events.domain.*;

@Component public class BudgetArticleView implements EntityView<BudgetArticle> {
 public Class<BudgetArticle> entity(){return BudgetArticle.class;}
 public void list(ListSpec<BudgetArticle> list){list.columns(BudgetArticle::getDescription,BudgetArticle::getBudgetCategory);}
 public void fields(EntityConfigBuilder<BudgetArticle> f){

 f.field(BudgetArticle::getCategory).hideInForm().hideInDetail();
 f.field(BudgetArticle::getDescription).label("Article").order(0);f.field(BudgetArticle::getBudgetCategory).order(10);

 }

}
