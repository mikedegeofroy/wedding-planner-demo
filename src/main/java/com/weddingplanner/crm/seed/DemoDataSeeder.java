package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.*;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.repository.MarketingPerformanceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@org.springframework.core.annotation.Order(0)
@Component
public class DemoDataSeeder implements CommandLineRunner {
    private final LeadInquiryRepository leads;
    private final MarketingPerformanceRepository performance;
    private final com.weddingplanner.crm.service.PipelineStageService stages;

    public DemoDataSeeder(LeadInquiryRepository leads, MarketingPerformanceRepository performance,
            com.weddingplanner.crm.service.PipelineStageService stages) {
        this.leads = leads;
        this.performance = performance;
        this.stages = stages;
    }

    @Override
    public void run(String... args) {
        if (leads.count() > 0) return;

        seedLead("Amelia & Noah", LeadChannel.INSTAGRAM, MarketingSource.META_ADS,
                "Lake Como 2027", "650000", LocalDate.of(2027, 6, 19), "Lake Como", 120,
                StageRole.QUALIFIED, 2,
                "Hi, we are planning a three-day wedding for around 120 guests at Lake Como in June 2027. Our budget is around €650k.",
                "VIP lead. Lake Como, June 2027, 120 guests, €650k. Routed to a founder; meeting booked.");
        seedLead("Olivia & James", LeadChannel.WEBSITE_FORM, MarketingSource.GOOGLE_ADS,
                "Italy destination weddings", "420000", LocalDate.of(2027, 9, 4), "Tuscany", 90,
                StageRole.QUALIFIED, 6,
                "We love Tuscany and expect about 90 guests. September 2027 would be ideal. Budget €400–450k.",
                "Target lead. Tuscany, September 2027, 90 guests, estimated budget midpoint €420k.");
        seedLead("Sophia & Daniel", LeadChannel.WHATSAPP, MarketingSource.REFERRAL,
                null, "900000", LocalDate.of(2027, 5, 22), "Lake Maggiore", 160,
                StageRole.QUALIFIED, 12,
                "We were referred by a former client. We are considering Lake Maggiore for 160 guests with up to €900k.",
                "VIP referral. Lake Maggiore, May 2027, 160 guests, up to €900k. Proposal in progress.");
        seedLead("Emma & William", LeadChannel.EMAIL, MarketingSource.PRESS,
                null, null, null, "Northern Italy", 70,
                StageRole.NEW, 1,
                "We saw your wedding in Vogue and would love to understand how you work. Around 70 guests in Northern Italy.",
                "Promising press lead. Budget and date are missing; ask two questions before routing.");
        seedLead("Charlotte & Henry", LeadChannel.INSTAGRAM, MarketingSource.ORGANIC_INSTAGRAM,
                null, "220000", LocalDate.of(2027, 8, 14), "Lake Como", 80,
                StageRole.DISQUALIFIED, 20,
                "Lake Como next August, 80 people. We hope to stay near €220k.",
                "Below current target budget. Keep a graceful referral response; no founder time required.");
        seedLead("Isabella & Lucas", LeadChannel.WEBSITE_FORM, MarketingSource.META_ADS,
                "Luxury villas Italy", "780000", LocalDate.of(2027, 7, 10), "Amalfi Coast", 140,
                StageRole.WON, 35,
                "Three days on the Amalfi Coast for 140 guests, July 2027, with a €750–800k budget.",
                "VIP Meta lead. Amalfi Coast, July 2027, 140 guests, €780k. Contract and deposit confirmed.");

        seedPerformance("Meta Ads · last 90 days", MarketingSource.META_ADS, "18000", 24, 9, 5, 2);
        seedPerformance("Google Ads · last 90 days", MarketingSource.GOOGLE_ADS, "9200", 18, 7, 2, 1);
        seedPerformance("Organic Instagram · last 90 days", MarketingSource.ORGANIC_INSTAGRAM, "0", 11, 4, 1, 1);
        seedPerformance("Referrals · last 90 days", MarketingSource.REFERRAL, "0", 5, 4, 3, 2);
        seedPerformance("Press · last 90 days", MarketingSource.PRESS, "2400", 4, 2, 1, 0);
    }

    private void seedLead(String couple, LeadChannel channel, MarketingSource source, String campaign,
                          String budget, LocalDate weddingDate, String location, int guests,
                          StageRole stage, int receivedDaysAgo, String message, String summary) {
        LeadInquiry lead = new LeadInquiry();
        lead.setCoupleName(couple);
        lead.setChannel(channel);
        lead.setSource(source);
        lead.setCampaign(campaign);
        if (budget != null) lead.setBudget(new BigDecimal(budget));
        lead.setWeddingDate(weddingDate);
        lead.setPreferredLocation(location);
        lead.setGuestCount(guests);
        // Seeded leads name the stage they mean rather than an id, so the demo still seeds correctly
        // against a pipeline the team has renamed.
        stages.byRole(stage).ifPresent(lead::setStage);
        lead.setDate(LocalDateTime.now().minusDays(receivedDaysAgo));
        lead.setFirstResponseAt(lead.getDate().plusMinutes(1));
        lead.setOriginalMessage(message);
        lead.setAiSummary(summary);
        leads.save(lead);
    }

    private void seedPerformance(String label, MarketingSource source, String spend, int leadsCount,
                                 int qualified, int vip, int bookings) {
        MarketingPerformance row = new MarketingPerformance();
        row.setDescription(label);
        // Rows are keyed by period + source + campaign, and MarketingRollupService buckets a period
        // to the first of its month. Anchoring the demo spend to the current month puts it on the
        // same key the seeded leads roll up into, so a rollup updates these rows instead of
        // creating a second set beside them.
        row.setPeriod(LocalDate.now().withDayOfMonth(1));
        row.setSource(source);
        row.setSpend(new BigDecimal(spend));
        row.setLeads(leadsCount);
        row.setQualifiedLeads(qualified);
        row.setVipLeads(vip);
        row.setBookings(bookings);
        performance.save(row);
    }
}
