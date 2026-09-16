package com.weddingplanner.crm.events.repository;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;
import com.weddingplanner.crm.events.domain.EventBudget;
@Repository public interface EventBudgetRepository extends DocumentRepository<EventBudget> {}
