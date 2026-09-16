package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.MarketingPerformance;
import com.weddingplanner.crm.domain.MarketingSource;
import com.weddingplanner.crm.domain.UtmMedium;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.repository.MarketingPerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recomputes the results half of {@link MarketingPerformance} from the lead documents.
 *
 * <p>Spend is imported (a person or an ad-platform job owns those columns); leads, meetings,
 * bookings and revenue are <em>derived</em>, never typed in. That is the whole point of the split:
 * a cost-per-booking figure is only worth reading if the bookings in the denominator are the same
 * bookings the sales team can open in the lead list.</p>
 *
 * <p><b>Attribution.</b> A channel row is credited with the leads whose <b>last touch</b> it was —
 * the click that produced the inquiry — which is the model ad platforms bill against, so CPL and
 * CAC compare with what Meta and Google report. First-touch credit stays on the lead itself
 * ({@code source}) and the Lead intelligence page charts both, because the two models disagree
 * exactly where the interesting leads are.</p>
 *
 * <p><b>Period.</b> A lead counts in the month it arrived, and its revenue counts there too, even
 * when the booking is signed months later. Crediting revenue to the month it was signed would
 * divide it by a spend that did not produce it.</p>
 */
@Service
public class MarketingRollupService {

    private final LeadInquiryRepository leads;
    private final MarketingPerformanceRepository performance;

    public MarketingRollupService(LeadInquiryRepository leads, MarketingPerformanceRepository performance) {
        this.leads = leads;
        this.performance = performance;
    }

    /** Results accumulated for one (month, source, campaign) cell. */
    private static final class Totals {
        final List<LeadInquiry> members = new ArrayList<>();
        int leads;
        int qualified;
        int vip;
        int meetings;
        int bookings;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal weddingValue = BigDecimal.ZERO;
        BigDecimal pipeline = BigDecimal.ZERO;
        UtmMedium medium;
        String utmSource;
    }

    private record Key(LocalDate period, MarketingSource source, String campaign) {}

    /**
     * Rewrite every channel row's results from the current lead data, adding rows for channels that
     * produced leads without spend. Returns the number of rows written.
     */
    @Transactional
    public int recompute() {
        Map<Key, Totals> totals = new LinkedHashMap<>();
        for (LeadInquiry lead : leads.findAllActive()) {
            if (lead.getDate() == null) continue;
            MarketingSource credited = lead.getLastTouchSource() != null
                    ? lead.getLastTouchSource()
                    : lead.getSource();
            if (credited == null) continue;
            Key key = new Key(month(lead.getDate().toLocalDate()), credited, campaign(lead.getCampaign()));
            Totals cell = totals.computeIfAbsent(key, ignored -> new Totals());
            cell.members.add(lead);
            cell.leads++;
            if (lead.isQualified()) cell.qualified++;
            if (lead.isVip()) cell.vip++;
            if (lead.isMet()) cell.meetings++;
            if (lead.isBooked()) cell.bookings++;
            cell.revenue = cell.revenue.add(nonNull(lead.getBookedFee()));
            cell.weddingValue = cell.weddingValue.add(nonNull(lead.getBookedValue()));
            cell.pipeline = cell.pipeline.add(nonNull(lead.getPipelineValue()));
            if (cell.medium == null) cell.medium = lead.getUtmMedium();
            if (cell.utmSource == null) cell.utmSource = lead.getUtmSource();
        }

        List<MarketingPerformance> rows = new ArrayList<>(performance.findAllActive());
        Map<Key, MarketingPerformance> byKey = new LinkedHashMap<>();
        for (MarketingPerformance row : rows) {
            if (row.getPeriod() == null || row.getSource() == null) continue;
            byKey.putIfAbsent(new Key(month(row.getPeriod()), row.getSource(), campaign(row.getCampaign())), row);
        }

        int written = 0;
        for (Map.Entry<Key, Totals> entry : totals.entrySet()) {
            Key key = entry.getKey();
            MarketingPerformance row = byKey.get(key);
            if (row == null) {
                // A channel that produced leads and cost nothing is still a channel. Creating the
                // row keeps referral and organic on the same table as the paid ones instead of
                // making them invisible for lack of an invoice.
                row = new MarketingPerformance();
                row.setPeriod(key.period());
                row.setSource(key.source());
                row.setCampaign(key.campaign().isBlank() ? null : key.campaign());
                row.setMedium(entry.getValue().medium == null ? UtmMedium.NONE : entry.getValue().medium);
                row.setUtmSource(entry.getValue().utmSource);
                row.setSpend(BigDecimal.ZERO);
                byKey.put(key, row);
            }
            apply(row, entry.getValue());
            row.setDescription(label(key));
            performance.save(row);
            pushCostDown(row, entry.getValue());
            written++;
        }

        // A spend row whose leads have all been deleted or re-attributed must fall back to zero,
        // not keep yesterday's count and quietly report a cost per booking that no longer exists.
        for (Map.Entry<Key, MarketingPerformance> entry : byKey.entrySet()) {
            if (totals.containsKey(entry.getKey())) continue;
            MarketingPerformance row = entry.getValue();
            apply(row, new Totals());
            performance.save(row);
            written++;
        }
        return written;
    }

    /**
     * Spread the cell's spend evenly across the leads it produced and store it on each lead.
     *
     * <p>Even division is the honest choice here: the ad platform bills for the cell, not the lead,
     * so any per-lead weighting we invented (by budget, by stage) would be a model dressed up as a
     * measurement — and it would make expensive leads look expensive because they were valuable.
     * With cost on the lead, {@code avg(acquisitionCost)} over any slice the dashboard can group by
     * is that slice's true cost per lead.</p>
     */
    private void pushCostDown(MarketingPerformance row, Totals totals) {
        if (totals.members.isEmpty()) return;
        BigDecimal spend = row.getSpend() == null ? BigDecimal.ZERO : row.getSpend();
        BigDecimal perLead = spend.signum() == 0
                ? BigDecimal.ZERO
                : spend.divide(BigDecimal.valueOf(totals.members.size()), 2, java.math.RoundingMode.HALF_UP);
        for (LeadInquiry lead : totals.members) {
            if (perLead.compareTo(nonNull(lead.getAcquisitionCost())) == 0) continue;
            lead.setAcquisitionCost(perLead);
            leads.save(lead);
        }
    }

    private static void apply(MarketingPerformance row, Totals totals) {
        row.setLeads(totals.leads);
        row.setQualifiedLeads(totals.qualified);
        row.setVipLeads(totals.vip);
        row.setMeetings(totals.meetings);
        row.setBookings(totals.bookings);
        row.setRevenue(totals.revenue);
        row.setBookedWeddingValue(totals.weddingValue);
        row.setPipeline(totals.pipeline);
    }

    private static String label(Key key) {
        String campaign = key.campaign().isBlank() ? "no campaign" : key.campaign();
        return key.source().name() + " · " + campaign + " · " + key.period();
    }

    private static LocalDate month(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    private static String campaign(String value) {
        return value == null ? "" : value.trim();
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
