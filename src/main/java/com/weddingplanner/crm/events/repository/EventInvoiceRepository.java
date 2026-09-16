package com.weddingplanner.crm.events.repository;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;
import com.weddingplanner.crm.events.domain.EventInvoice;
@Repository public interface EventInvoiceRepository extends DocumentRepository<EventInvoice> {}
