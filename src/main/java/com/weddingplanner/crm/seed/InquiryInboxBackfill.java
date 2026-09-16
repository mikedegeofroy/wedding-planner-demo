package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.service.InquiryInboxService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class InquiryInboxBackfill implements CommandLineRunner {
    private final LeadInquiryRepository leads;
    private final InquiryInboxService inbox;
    public InquiryInboxBackfill(LeadInquiryRepository leads, InquiryInboxService inbox) {
        this.leads = leads; this.inbox = inbox;
    }
    public void run(String... args) {
        for (var lead : leads.findAllActive()) inbox.importInquiry(lead.getId());
    }
}
