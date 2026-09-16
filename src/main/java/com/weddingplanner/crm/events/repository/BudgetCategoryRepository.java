package com.weddingplanner.crm.events.repository;
import org.springframework.stereotype.Repository;
import su.onno.repository.CatalogRepository;
import com.weddingplanner.crm.events.domain.BudgetCategory;
@Repository public interface BudgetCategoryRepository extends CatalogRepository<BudgetCategory> {}
