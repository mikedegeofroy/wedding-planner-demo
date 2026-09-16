package com.weddingplanner.crm.events.repository;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;
import com.weddingplanner.crm.events.domain.EventPayment;
@Repository public interface EventPaymentRepository extends DocumentRepository<EventPayment> {}
