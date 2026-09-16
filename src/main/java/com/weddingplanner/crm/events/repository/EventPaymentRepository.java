package com.weddingplanner.crm.events.repository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;
import com.weddingplanner.crm.events.domain.EventPayment;
@Repository public interface EventPaymentRepository extends DocumentRepository<EventPayment> {
    /** What has already been transferred against one invoice, however many boots wrote it. */
    List<EventPayment> findByInvoiceAndDeletionMarkFalse(UUID invoice);
}
