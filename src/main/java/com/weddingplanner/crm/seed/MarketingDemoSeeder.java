package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.*;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.repository.MarketingPerformanceRepository;
import com.weddingplanner.crm.service.MarketingRollupService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A year of acquisition history, so the marketing pages have something to be right about.
 *
 * <p>Six hand-written leads make a good story and a useless report: every rate is 0%, 17% or 100%,
 * and no chart has a shape. This generates a year of inquiries whose channels behave differently on
 * purpose — referral and press produce few, expensive-to-earn, high-budget couples that close;
 * organic Instagram produces volume that mostly does not qualify; paid search converts better than
 * paid social at a higher cost per click. Those are the differences the pages exist to show, and a
 * demo that shows them is the one worth reviewing.</p>
 *
 * <p>Deterministic: the same seed yields the same year, so a number quoted in a demo is still there
 * the next morning. It runs only when the dataset is still the thin one, and never deletes a lead.
 * Set {@code planner.marketing.demo-history=false} to keep the bare six — which is what the inbox and
 * merge suites do, since they assert against an exact, hand-written set of inquiries.</p>
 */
@Order(1)
@Component
@ConditionalOnProperty(name = "planner.marketing.demo-history", matchIfMissing = true)
public class MarketingDemoSeeder implements CommandLineRunner {

    /** Below this the dataset is still the six-lead demo and wants filling out. */
    private static final int THIN_DATASET = 40;
    // Two years, not one: every stat tile on the operations board compares against the previous
    // period, and at the 1y preset a single year of history leaves that comparison with nothing to
    // read. The seasonality curve also only looks like a curve once it has repeated.
    private static final int MONTHS = 24;

    private final LeadInquiryRepository leads;
    private final MarketingPerformanceRepository performance;
    private final MarketingRollupService rollup;
    private final com.weddingplanner.crm.service.PipelineStageService stages;

    public MarketingDemoSeeder(LeadInquiryRepository leads, MarketingPerformanceRepository performance,
                               MarketingRollupService rollup,
                               com.weddingplanner.crm.service.PipelineStageService stages) {
        this.leads = leads;
        this.performance = performance;
        this.rollup = rollup;
        this.stages = stages;
    }

    /**
     * How one acquisition channel behaves: how much of it there is, what it is worth, and how often
     * it survives each step of the funnel.
     */
    private record ChannelProfile(MarketingSource source, UtmMedium medium, String utmSource,
                                  LeadChannel channel, String[] campaigns, String[] landingPages,
                                  int leadsPerMonth, int budgetMean, int budgetSpread,
                                  double completeOdds, double meetingOdds, double bookingOdds,
                                  int responseMinutesTypical, BigDecimal monthlySpend,
                                  int monthlyImpressions, int monthlyClicks,
                                  double retargetedOdds) {}

    // Volumes and conversion were raised once the board had a 90-day window on it: at the old rates
    // a quarter produced two bookings across six channels, so the channel table read 0% on four rows
    // and the booked-value line sat on the axis. The relative story is unchanged — referral is still
    // the channel that closes, organic Instagram still brings volume that mostly does not qualify —
    // because that contrast is what the marketing pages exist to show.
    // retargetedOdds is how often a couple who first met Wedding Planner here comes back through a paid ad and
    // writes from that click instead. It is the whole reason the two attribution models disagree:
    // press and organic create the demand, and the retargeting campaign gets billed for the click.
    // Monthly spend is set so cost per lead lands where this market actually sits: a few hundred
    // euro for paid social, more for paid search, and most expensive of all for the PR retainer that
    // produces one couple a month. Those relative costs are the point of the comparison charts.
    private static final ChannelProfile[] CHANNELS = {
            new ChannelProfile(MarketingSource.META_ADS, UtmMedium.PAID_SOCIAL, "instagram",
                    LeadChannel.INSTAGRAM,
                    new String[]{"lake-como-2027", "luxury-villas-italy", "retargeting-site-visitors"},
                    new String[]{"/weddings/lake-como", "/weddings/villas", "/enquire"},
                    13, 310_000, 220_000, 0.62, 0.38, 0.14, 9,
                    new BigDecimal("1800"), 240_000, 2_400, 0.0),
            new ChannelProfile(MarketingSource.GOOGLE_ADS, UtmMedium.CPC, "google",
                    LeadChannel.WEBSITE_FORM,
                    new String[]{"destination-wedding-planner", "wedding-planner-tuscany"},
                    new String[]{"/destination-weddings", "/weddings/tuscany"},
                    8, 375_000, 190_000, 0.78, 0.50, 0.22, 7,
                    new BigDecimal("1600"), 34_000, 900, 0.0),
            new ChannelProfile(MarketingSource.ORGANIC_INSTAGRAM, UtmMedium.ORGANIC_SOCIAL, "instagram",
                    LeadChannel.INSTAGRAM,
                    new String[]{},
                    new String[]{"/", "/portfolio"},
                    11, 215_000, 160_000, 0.48, 0.26, 0.08, 900,
                    BigDecimal.ZERO, 0, 0, 0.35),
            new ChannelProfile(MarketingSource.REFERRAL, UtmMedium.REFERRAL, "referral",
                    LeadChannel.WHATSAPP,
                    new String[]{"past-client", "venue-partner"},
                    new String[]{"/enquire"},
                    5, 620_000, 240_000, 0.92, 0.78, 0.45, 5,
                    BigDecimal.ZERO, 0, 0, 0.15),
            new ChannelProfile(MarketingSource.PRESS, UtmMedium.PR, "vogue.com",
                    LeadChannel.EMAIL,
                    new String[]{"vogue-feature", "bazaar-real-wedding"},
                    new String[]{"/press", "/portfolio"},
                    3, 510_000, 230_000, 0.70, 0.56, 0.28, 90,
                    new BigDecimal("850"), 0, 0, 0.35),
            new ChannelProfile(MarketingSource.DIRECT, UtmMedium.NONE, "(direct)",
                    LeadChannel.EMAIL,
                    new String[]{},
                    new String[]{"/", "/enquire"},
                    6, 300_000, 180_000, 0.55, 0.34, 0.18, 300,
                    BigDecimal.ZERO, 0, 0, 0.20),
    };

    private static final String[] FIRST_NAMES = {
            "Amelia", "Olivia", "Sophia", "Emma", "Charlotte", "Isabella", "Mia", "Grace", "Alice",
            "Freya", "Elena", "Camille", "Beatrice", "Chiara", "Nina", "Valentina", "Lucia", "Marta",
            "Anna", "Rose", "Hannah", "Lara", "Julia", "Eva", "Sienna"};
    private static final String[] SECOND_NAMES = {
            "Noah", "James", "Daniel", "William", "Henry", "Lucas", "Theo", "Edward", "Marco",
            "Alessandro", "Tom", "Oliver", "Felix", "Hugo", "Matteo", "Rafael", "Jack", "Leo",
            "Samuel", "Adam", "Nikolas", "Pierre", "Max", "Elias", "Ben"};
    private static final String[] LOCATIONS = {
            "Lake Como", "Tuscany", "Amalfi Coast", "Lake Maggiore", "Puglia", "Sicily",
            "Portofino", "Venice", "Capri", "Umbria"};
    private static final LostReason[] LOST_REASONS = {
            LostReason.BUDGET, LostReason.DATE_UNAVAILABLE, LostReason.COMPETITOR,
            LostReason.WENT_QUIET, LostReason.POSTPONED};

    @Override
    public void run(String... args) {
        if (leads.count() >= THIN_DATASET) {
            // Already filled out (or a real dataset). Keep the derived half honest anyway: spend is
            // imported, results are derived, and redoing the derived half at boot is cheap.
            backfillAttribution();
            rollup.recompute();
            return;
        }

        dropLegacySummaryRows();

        Random random = new Random(20260911L);
        LocalDate firstMonth = LocalDate.now().withDayOfMonth(1).minusMonths(MONTHS - 1L);
        int couple = 0;

        for (int monthIndex = 0; monthIndex < MONTHS; monthIndex++) {
            LocalDate month = firstMonth.plusMonths(monthIndex);
            // Weddings are seasonal and so is the demand for them: enquiries build through winter
            // for the following summer. A flat year would make every trend line meaningless.
            double seasonality = seasonality(month.getMonthValue());
            for (ChannelProfile channel : CHANNELS) {
                seedSpend(channel, month, seasonality);
                int count = poisson(random, channel.leadsPerMonth() * seasonality * growth(monthIndex));
                for (int i = 0; i < count; i++) {
                    seedLead(channel, month, random, couple++);
                }
            }
        }
        backfillAttribution();
        rollup.recompute();
    }

    /**
     * Give every lead that predates the attribution columns a UTM triple and a minimal touch
     * history, and re-save it so the derived reporting columns exist.
     *
     * <p>A schema migration adds a column as {@code NULL}; it cannot run {@code beforeWrite} for
     * rows already on disk. Without this pass the older leads sit in the same tables with empty
     * pipeline values and blank rate columns, and every average silently excludes them — which is
     * the worst of the three possible outcomes, because the page still looks right.</p>
     */
    private void backfillAttribution() {
        for (LeadInquiry lead : leads.findAllActive()) {
            boolean needsAttribution = lead.getUtmSource() == null || lead.getUtmSource().isBlank();
            boolean needsTouches = lead.getTouches() == null || lead.getTouches().isEmpty();
            boolean needsDerived = lead.getPipelineValue() == null || lead.getQualifiedRate() == null;
            String campaign = slug(lead.getCampaign());
            boolean needsCampaignSlug = campaign != null && !campaign.equals(lead.getCampaign());
            // Read the stage, not the isLost() flag: on a row that predates the column the flag is
            // still false here and only becomes true when beforeWrite runs on the save below.
            boolean needsLostReason = lead.getStageRole() != null && lead.getStageRole().lost()
                    && lead.getLostReason() == null;
            if (!needsAttribution && !needsTouches && !needsDerived
                    && !needsCampaignSlug && !needsLostReason) continue;

            ChannelProfile channel = profileFor(lead.getSource());
            if (needsCampaignSlug) {
                // The first demo wrote campaigns as prose ("Lake Como 2027"); a utm_campaign is a
                // slug. Left alone, one campaign would show up as two bars on every chart.
                lead.setCampaign(campaign);
            }
            if (needsLostReason) {
                // A lost lead with no reason is an empty bar on the loss chart that reads as a
                // category rather than as missing data. Below-target budget is the demo's own rule.
                lead.setLostReason(lead.getBudget() == null
                        || lead.getBudget().compareTo(BigDecimal.valueOf(300_000)) < 0
                        ? LostReason.BUDGET
                        : LostReason.WENT_QUIET);
            }
            if (needsAttribution) {
                lead.setUtmSource(channel.utmSource());
                lead.setUtmMedium(channel.medium());
                if (lead.getLandingPage() == null) {
                    lead.setLandingPage(channel.landingPages()[0]);
                }
            }
            if (needsTouches) {
                lead.setTouches(touches(lead, channel, channel,
                        new Random(lead.getId().getMostSignificantBits()),
                        lead.isMet(), lead.isBooked()));
            }
            leads.save(lead);
        }
    }

    /** "Lake Como 2027" → "lake-como-2027": the shape a utm_campaign actually arrives in. */
    private static String slug(String campaign) {
        if (campaign == null || campaign.isBlank()) return null;
        return campaign.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private static ChannelProfile profileFor(MarketingSource source) {
        for (ChannelProfile channel : CHANNELS) {
            if (channel.source() == source) return channel;
        }
        return CHANNELS[CHANNELS.length - 1];
    }

    /**
     * The five all-time summary rows the first demo shipped predate the period column and are a
     * second, hand-typed answer to a question the rollup now derives. Two answers is worse than one.
     */
    private void dropLegacySummaryRows() {
        for (MarketingPerformance row : performance.findAllActive()) {
            if (row.getPeriod() == null) {
                // Deletion is soft: the row is written back with its deletion mark, so it has to
                // satisfy the now-required period on the way out. Stamping the current month keeps
                // the tombstone valid — the read layer hides it either way.
                row.setPeriod(LocalDate.now().withDayOfMonth(1));
                performance.delete(row);
            }
        }
    }

    private void seedSpend(ChannelProfile channel, LocalDate month, double seasonality) {
        if (channel.monthlySpend().signum() == 0) return;
        String[] campaigns = channel.campaigns().length == 0 ? new String[]{null} : channel.campaigns();
        BigDecimal share = channel.monthlySpend()
                .multiply(BigDecimal.valueOf(seasonality))
                .divide(BigDecimal.valueOf(campaigns.length), 2, java.math.RoundingMode.HALF_UP);
        for (String campaign : campaigns) {
            MarketingPerformance row = new MarketingPerformance();
            row.setPeriod(month);
            row.setSource(channel.source());
            row.setMedium(channel.medium());
            row.setUtmSource(channel.utmSource());
            row.setCampaign(campaign);
            row.setSpend(share);
            row.setImpressions((int) (channel.monthlyImpressions() * seasonality / campaigns.length));
            row.setClicks((int) (channel.monthlyClicks() * seasonality / campaigns.length));
            row.setDescription(channel.source().name() + " · "
                    + (campaign == null ? "no campaign" : campaign) + " · " + month);
            performance.save(row);
        }
    }

    private void seedLead(ChannelProfile channel, LocalDate month, Random random, int coupleIndex) {
        LocalDateTime received = month
                .plusDays(random.nextInt(month.lengthOfMonth()))
                .atTime(9 + random.nextInt(11), random.nextInt(60));
        if (received.isAfter(LocalDateTime.now())) return;

        // Who created the demand, and who got the click that produced the inquiry. For most leads
        // they are the same channel; for a retargeted couple they are not, and that gap is what the
        // two attribution charts on the Lead intelligence page exist to show.
        boolean retargeted = random.nextDouble() < channel.retargetedOdds();
        ChannelProfile converting = retargeted ? profileFor(MarketingSource.META_ADS) : channel;

        LeadInquiry lead = new LeadInquiry();
        // Vary the second name on a slower counter than the first: stepping both by the index
        // repeats the same 25 couples every 25 leads, which reads as duplicated data rather than as
        // a year of different people.
        // The family name steps once per run through the first names, so no two couples in a demo
        // this size are the same pair of people — a card is recognised by the whole name, and the
        // reference pickers that offer contacts are unusable when fifty of them read "William".
        String first = FIRST_NAMES[coupleIndex % FIRST_NAMES.length];
        String family = ContactIdentityBackfill.familyName(coupleIndex / FIRST_NAMES.length);
        lead.setCoupleName(first + " & "
                + SECOND_NAMES[(coupleIndex / FIRST_NAMES.length) % SECOND_NAMES.length] + " " + family);
        lead.setEmail((first + "." + family).toLowerCase(java.util.Locale.ROOT) + "@example.com");
        lead.setDate(received);
        lead.setChannel(converting.channel());
        // The website form asks for a phone beside the email, and most couples fill both in. It is
        // the only point in this demo where a number is captured on arrival, and it is what gives a
        // form lead a WhatsApp identity to answer on instead of a mailbox and nothing else.
        if (converting.channel() == LeadChannel.WEBSITE_FORM && random.nextInt(3) > 0)
            lead.setPhone(String.format("+39 3%02d %03d %04d",
                    random.nextInt(100), random.nextInt(1000), random.nextInt(10000)));
        lead.setSource(channel.source());
        lead.setUtmSource(converting.utmSource());
        lead.setUtmMedium(converting.medium());
        lead.setLandingPage(pick(random, converting.landingPages()));
        String campaign = retargeted
                ? "retargeting-site-visitors"
                : (channel.campaigns().length == 0 ? null : pick(random, channel.campaigns()));
        lead.setCampaign(campaign);
        if (campaign != null && converting.medium() == UtmMedium.PAID_SOCIAL) {
            lead.setUtmContent(random.nextBoolean() ? "carousel-villa" : "reel-ceremony");
        }
        if (converting.medium() == UtmMedium.CPC) {
            lead.setUtmTerm(random.nextBoolean() ? "wedding planner italy" : "destination wedding planner");
        }
        if (channel.source() == MarketingSource.PRESS) {
            lead.setReferrer(channel.utmSource());
        }

        boolean complete = random.nextDouble() < channel.completeOdds();
        int budget = Math.max(60_000,
                (int) Math.round(channel.budgetMean() + random.nextGaussian() * channel.budgetSpread() / 2));
        budget = budget / 10_000 * 10_000;
        lead.setBudget(BigDecimal.valueOf(budget));
        lead.setGuestCount(40 + random.nextInt(140));
        String location = LOCATIONS[random.nextInt(LOCATIONS.length)];
        if (complete) {
            lead.setPreferredLocation(location);
            lead.setWeddingDate(received.toLocalDate().plusMonths(10 + random.nextInt(16)));
        } else {
            // An incomplete inquiry is the normal case, not a data error: the couple wrote before
            // they had a date or a number. Which field is missing is what the team chases next.
            if (random.nextBoolean()) {
                lead.setPreferredLocation(location);
            } else {
                lead.setWeddingDate(received.toLocalDate().plusMonths(12 + random.nextInt(12)));
            }
            if (random.nextDouble() < 0.5) {
                lead.setBudget(null);
            }
        }

        int responseMinutes = Math.max(1,
                (int) Math.round(channel.responseMinutesTypical() * (0.3 + random.nextDouble() * 2.2)));
        lead.setFirstResponseAt(received.plusMinutes(responseMinutes));

        boolean qualifies = complete && lead.getBudget() != null
                && lead.getBudget().compareTo(BigDecimal.valueOf(300_000)) >= 0;
        boolean met = qualifies && random.nextDouble() < channel.meetingOdds();
        boolean booked = met && random.nextDouble() < channel.bookingOdds() / Math.max(0.01, channel.meetingOdds());

        // Met and proposed-to are not stages any more — both are recorded as touchpoints below and
        // read off the funnel from there, so a couple being worked on is simply qualified.
        StageRole stage;
        if (booked) {
            stage = StageRole.WON;
        } else if (qualifies) {
            stage = StageRole.QUALIFIED;
        } else {
            stage = StageRole.NEW;
        }
        // Everything old enough to have gone cold either closed or did not. Leaving a year-old
        // inquiry sitting in "qualifying" would inflate the open pipeline with dead weight.
        long ageDays = java.time.Duration.between(received, LocalDateTime.now()).toDays();
        if (!booked && ageDays > 150 && random.nextDouble() < 0.75) {
            // A couple worth winning that got away is a different loss from one that never fit,
            // and the demo is only interesting if the two are counted apart.
            stage = qualifies ? StageRole.LOST : StageRole.DISQUALIFIED;
            lead.setLostReason(lead.getBudget() == null
                    || lead.getBudget().compareTo(BigDecimal.valueOf(300_000)) < 0
                    ? LostReason.BUDGET
                    : LOST_REASONS[random.nextInt(LOST_REASONS.length)]);
        }
        stages.byRole(stage).ifPresent(lead::setStage);

        if (met) {
            lead.setMeetingAt(received.plusDays(3 + random.nextInt(18)));
        }
        if (booked) {
            lead.setBookedAt(received.plusDays(25 + random.nextInt(70)));
        }

        lead.setOriginalMessage(message(lead, location));
        lead.setAiSummary(summary(lead, converting));
        lead.setTouches(touches(lead, channel, converting, random, met, booked));
        leads.save(lead);
    }

    /**
     * The contact history behind the inquiry. An ad-sourced couple clicks and browses before they
     * write; a referral arrives already warm. Both end up as "one lead", which is exactly why the
     * touch history is worth keeping.
     */
    private List<LeadTouchpoint> touches(LeadInquiry lead, ChannelProfile discovered,
                                         ChannelProfile converting, Random random,
                                         boolean met, boolean booked) {
        List<LeadTouchpoint> rows = new ArrayList<>();
        LocalDateTime received = lead.getDate();
        boolean paidDiscovery = discovered.medium() == UtmMedium.PAID_SOCIAL
                || discovered.medium() == UtmMedium.CPC;

        if (paidDiscovery) {
            rows.add(touch(received.minusDays(2 + random.nextInt(20)), TouchType.AD_CLICK,
                    discovered.channel(), discovered.source(), lead.getCampaign()));
            rows.add(touch(received.minusDays(1 + random.nextInt(3)), TouchType.SITE_VISIT,
                    LeadChannel.WEBSITE_FORM, discovered.source(), lead.getLandingPage()));
        } else {
            rows.add(touch(received.minusDays(2 + random.nextInt(40)), TouchType.SITE_VISIT,
                    LeadChannel.WEBSITE_FORM, discovered.source(), "Found us through " + discovered.utmSource()));
        }

        // The retargeting click that brought them back. It is the last sourced touch, so the header
        // derives its last-touch credit from here while first touch stays with the discovery above.
        if (converting != discovered) {
            rows.add(touch(received.minusHours(2 + random.nextInt(40)), TouchType.AD_CLICK,
                    converting.channel(), converting.source(), lead.getCampaign()));
        }

        rows.add(touch(received,
                converting.channel() == LeadChannel.WEBSITE_FORM ? TouchType.FORM_SUBMIT : TouchType.INBOUND_MESSAGE,
                converting.channel(), converting.source(), "First enquiry"));
        if (lead.getFirstResponseAt() != null) {
            rows.add(touch(lead.getFirstResponseAt(), TouchType.OUTBOUND_REPLY,
                    converting.channel(), null, "First reply"));
            rows.add(touch(lead.getFirstResponseAt().plusDays(1 + random.nextInt(4)),
                    TouchType.INBOUND_MESSAGE, converting.channel(), null, "Answered our questions"));
        }
        if (met && lead.getMeetingAt() != null) {
            rows.add(touch(lead.getMeetingAt().minusDays(1), TouchType.CALL,
                    LeadChannel.WHATSAPP, null, "Intro call"));
            rows.add(touch(lead.getMeetingAt(), TouchType.VIDEO_MEETING,
                    LeadChannel.EMAIL, null, "Discovery meeting"));
        }
        if (booked) {
            // Backfilled leads can be booked without either timestamp on file; anchor to whichever
            // one exists rather than inventing a meeting that was never recorded.
            LocalDateTime anchor = lead.getMeetingAt() != null ? lead.getMeetingAt()
                    : lead.getBookedAt() != null ? lead.getBookedAt().minusDays(30)
                    : received.plusDays(14);
            rows.add(touch(anchor.plusDays(7), TouchType.VENUE_VISIT,
                    LeadChannel.WHATSAPP, null, "Site visit with the couple"));
            rows.add(touch(anchor.plusDays(17), TouchType.PROPOSAL_SENT,
                    LeadChannel.EMAIL, null, "Proposal and estimate sent"));
        }
        return rows;
    }

    private static LeadTouchpoint touch(LocalDateTime at, TouchType type, LeadChannel channel,
                                        MarketingSource source, String detail) {
        LeadTouchpoint row = new LeadTouchpoint();
        row.setAt(at);
        row.setType(type);
        row.setChannel(channel);
        row.setSource(source);
        row.setDetail(detail);
        return row;
    }

    private static String message(LeadInquiry lead, String location) {
        StringBuilder text = new StringBuilder("Hello — we are planning our wedding");
        if (lead.getPreferredLocation() != null) {
            text.append(" in ").append(lead.getPreferredLocation());
        } else {
            text.append(", probably somewhere like ").append(location);
        }
        if (lead.getGuestCount() != null) {
            text.append(" for around ").append(lead.getGuestCount()).append(" guests");
        }
        if (lead.getWeddingDate() != null) {
            text.append(", ideally ").append(lead.getWeddingDate().getMonth().name().toLowerCase())
                    .append(' ').append(lead.getWeddingDate().getYear());
        }
        text.append('.');
        if (lead.getBudget() != null) {
            text.append(" Our budget is around €").append(lead.getBudget().intValue() / 1000).append("k.");
        } else {
            text.append(" We have not settled on a budget yet.");
        }
        return text.toString();
    }

    private static String summary(LeadInquiry lead, ChannelProfile channel) {
        String missing = lead.getBudget() == null ? "budget"
                : lead.getWeddingDate() == null ? "date"
                : lead.getPreferredLocation() == null ? "location" : null;
        return "Arrived via " + channel.utmSource() + " (" + channel.medium().name().toLowerCase() + ")"
                + (lead.getCampaign() == null ? "" : ", campaign " + lead.getCampaign()) + ". "
                + (missing == null
                        ? "Budget, date and location are all on file."
                        : "Still missing the " + missing + " before this can be routed.");
    }

    /**
     * Enquiry volume across the year: quiet in high summer, busiest in the winter planning peak.
     *
     * <p>The trough used to be deep enough to swamp everything else. Every stat tile compares a
     * period against the one before it, and a 90-day window ending in September is comparing high
     * summer against spring — so the whole board rendered red on an agency that was doing fine. The
     * shape is the same, with less amplitude.
     */
    private static double seasonality(int month) {
        return switch (month) {
            case 1, 2 -> 1.20;
            case 3, 4 -> 1.10;
            case 5, 6 -> 0.95;
            case 7, 8 -> 0.85;
            case 9, 10 -> 1.10;
            default -> 1.15;
        };
    }

    /**
     * A business that grows. The newest month is the baseline and earlier months are scaled back
     * from it, so today's volume is the {@code leadsPerMonth} the channel table actually states and
     * the history ramps up to it rather than away from it.
     */
    private static double growth(int monthIndex) {
        return Math.pow(1.04, monthIndex - (MONTHS - 1.0));
    }

    /** Counts vary month to month; a fixed number per month would draw a suspiciously flat chart. */
    private static int poisson(Random random, double mean) {
        double limit = Math.exp(-mean);
        int count = 0;
        double product = random.nextDouble();
        while (product > limit && count < 40) {
            count++;
            product *= random.nextDouble();
        }
        return count;
    }

    private static String pick(Random random, String[] values) {
        return values.length == 0 ? null : values[random.nextInt(values.length)];
    }
}
