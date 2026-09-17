package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.PipelineStage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

/**
 * Which inquiry speaks for each couple, read without loading a single inquiry.
 *
 * <p>Two of the busiest surfaces need this map. The contact panel's fields resolve a couple's stage,
 * budget and wedding date through it, and the Clients inbox folders <em>are</em> the pipeline
 * stages, so filing one page of conversations asks for every couple's stage at once.
 *
 * <p>Both used to answer it by calling {@code findAllActive()} on the inquiries. That loads the
 * whole document — and an inquiry carries a tabular section of touchpoints, which Spring Data JDBC
 * fetches with a query of its own per row. Five hundred inquiries is therefore over a thousand
 * queries, for two columns. Under a handful of concurrent inbox reads the ten-connection pool was
 * drained and requests started failing on "Failed to obtain JDBC Connection" — which is what the
 * Conversations screen was showing when a workspace was switched.
 *
 * <p>So the three columns that decide the winner are read directly, in one query, and the inquiry
 * itself is loaded only for the handful of couples actually on screen. The result is memoised for a
 * moment and computed under a lock, so twenty requests arriving together take one scan between them
 * rather than twenty at once.
 */
@Component
public class LeadIndex {

    /** How long a scan stands. Long enough to cover a burst of reads, short enough that a stage
     *  moved a moment ago shows on the next breath. */
    private static final long TTL_NANOS = 2_000_000_000L;

    /** The winning inquiry per contact, and its stage — everything decided without a document read. */
    public record Snapshot(Map<UUID, UUID> leadByContact, Map<UUID, Ref<PipelineStage>> stageByContact) {}

    private final JdbcTemplate jdbc;
    private final PipelineStageService stages;

    private final Object lock = new Object();
    private volatile Snapshot snapshot;
    private volatile long takenAt;

    public LeadIndex(JdbcTemplate jdbc, PipelineStageService stages) {
        this.jdbc = jdbc;
        this.stages = stages;
    }

    public Snapshot current() {
        long now = System.nanoTime();
        Snapshot cached = snapshot;
        if (cached != null && now - takenAt < TTL_NANOS) return cached;
        synchronized (lock) {
            // Another thread may have refreshed it while this one waited, and re-scanning behind it
            // is exactly the pile-up this lock exists to prevent.
            cached = snapshot;
            if (cached != null && System.nanoTime() - takenAt < TTL_NANOS) return cached;
            Snapshot taken = scan();
            snapshot = taken;
            takenAt = System.nanoTime();
            return taken;
        }
    }

    /** Drop the memo, for a caller that has just moved a stage and wants to read its own write. */
    public void invalidate() {
        takenAt = 0L;
    }

    private Snapshot scan() {
        var leadByContact = new HashMap<UUID, UUID>();
        var stageByContact = new HashMap<UUID, Ref<PipelineStage>>();
        var positions = new HashMap<UUID, Integer>();
        jdbc.query("SELECT _id, contact, stage FROM document_lead_inquiries WHERE _deletion_mark = FALSE",
                rs -> {
                    UUID contact = rs.getObject("contact", UUID.class);
                    if (contact == null) return;
                    UUID id = rs.getObject("_id", UUID.class);
                    UUID stage = rs.getObject("stage", UUID.class);
                    Ref<PipelineStage> ref = stage == null ? null : Ref.of(PipelineStage.class, stage);
                    int position = ref == null ? -1 : stages.positionOf(ref);
                    Integer standing = positions.get(contact);
                    // The furthest inquiry along the pipeline speaks for the couple, so an older
                    // lost inquiry never drags a booked couple backwards.
                    if (standing != null && standing >= position) return;
                    positions.put(contact, position);
                    leadByContact.put(contact, id);
                    if (ref != null) stageByContact.put(contact, ref);
                    else stageByContact.remove(contact);
                });
        return new Snapshot(Map.copyOf(leadByContact), Map.copyOf(stageByContact));
    }
}
