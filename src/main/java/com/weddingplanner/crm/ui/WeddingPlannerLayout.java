package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.MarketingPerformance;
import org.springframework.stereotype.Component;
import su.onno.ui.Layout;
import su.onno.ui.LayoutSpec;
import su.onno.ui.NavStyle;

@Component
public class WeddingPlannerLayout implements Layout {
    @Override
    public void configure(LayoutSpec layout) {
        layout.shell()
                .nav(NavStyle.SIDEBAR)
                .brand("Wedding Planner")
                .light(p -> p.primary("#3474ED").primarySoft("#DBEAFE"))
                .dark(p -> p.primary("#3474ED").primarySoft("#172B50"));

        // Two inboxes, two rail entries. Each is the whole destination, so it is a direct link:
        // clicking the icon opens the screen instead of a drawer holding a single item.
        layout.link("/inbox", "Clients", "inbox").order(0);
        layout.link("/conversations", "Conversations", "messages-square").order(1);

        // Clients and contractors are the same Contacts catalog, split by inbox folder: two lists
        // people actually work in, plus the unfiltered catalog for the remaining folders
        // (partners, venues, competitors, team).
        layout.section("Sales").order(2).icon("sparkles")
                .page("/clients", "Clients", "heart-handshake")
                .page("/contractors", "Contractors", "hard-hat")
                .catalog(com.weddingplanner.crm.domain.Contact.class, "users")
                .document(LeadInquiry.class, "mail-question")
                .catalog(com.weddingplanner.crm.domain.PipelineStage.class, "flag");

        // Explicit nav glyphs: the name heuristic maps every "Event*" entity to the same
        // calendar, so the whole section rendered one repeated icon. Each row gets the
        // glyph for what it actually is instead.
        layout.section("Events").order(3).icon("calendar-days")
                .catalog(com.weddingplanner.crm.events.domain.EventProject.class, "party-popper")
                .document(com.weddingplanner.crm.events.domain.EventBudget.class, "calculator")
                .document(com.weddingplanner.crm.events.domain.EventInvoice.class, "receipt-text")
                .document(com.weddingplanner.crm.events.domain.EventPayment.class, "credit-card")
                .catalog(com.weddingplanner.crm.events.domain.BudgetArticle.class, "package")
                .catalog(com.weddingplanner.crm.events.domain.BudgetCategory.class, "folder-tree");

        // Two marketing questions, two pages. "What should we spend on" and "what kind of couple
        // does each channel send" are read by different people at different moments; one combined
        // board would be unreadable in a meeting. The channel table sits under its own page and
        // stays in the rail for anyone who wants the raw rows.
        layout.section("Marketing").order(4).icon("badge-euro")
                .page("/marketing", "Performance", "badge-euro")
                .page("/lead-intelligence", "Lead intelligence", "radar")
                .catalog(MarketingPerformance.class, "table");
    }
}
