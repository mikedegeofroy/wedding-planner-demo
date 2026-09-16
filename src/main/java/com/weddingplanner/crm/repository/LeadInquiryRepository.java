package com.weddingplanner.crm.repository;

import com.weddingplanner.crm.domain.LeadInquiry;
import org.springframework.stereotype.Repository;
import su.onno.repository.DocumentRepository;

@Repository
public interface LeadInquiryRepository extends DocumentRepository<LeadInquiry> {
}
