package com.weddingplanner.crm.events.repository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;
import com.weddingplanner.crm.events.domain.EventInvoice;
@Repository public interface EventInvoiceRepository extends DocumentRepository<EventInvoice> {
    /** Everything billed on one event, whoever raised it. */
    List<EventInvoice> findByEventAndDeletionMarkFalse(UUID event);
}
