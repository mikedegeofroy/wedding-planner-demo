package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.domain.StageRole;
import org.springframework.stereotype.Component;
import su.onno.migration.AppMigration;
import su.onno.migration.MigrationContext;
import su.onno.repository.EnumerationPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Fills in what each existing lead's stage <em>means</em>.
 *
 * <p>The pipeline used to be an enumeration and is now a catalog the planning team edits. The stage
 * column survives that untouched — the stages are seeded under the ids the enumeration persisted, so
 * every inquiry still points at its own stage — but the derived {@code stageRole} beside it is new,
 * and a lead nobody has saved since would read as untouched: a lost lead would stop reading as lost
 * in the list, and the brief card would advise chasing a wedding that is already booked.</p>
 */
@Component
public class PipelineStageRoleBackfill implements AppMigration {

    /** The stage constants the enumeration carried, and what each of them meant. */
    private static final Map<String, StageRole> ROLES = Map.of(
            "NEW", StageRole.NEW,
            "QUALIFYING", StageRole.QUALIFYING,
            "QUALIFIED", StageRole.QUALIFIED,
            "MEETING_BOOKED", StageRole.MEETING,
            "PROPOSAL", StageRole.PROPOSAL,
            "BOOKED", StageRole.WON,
            "LOST", StageRole.LOST);

    @Override
    public String version() {
        return "2026.09.14.1";
    }

    @Override
    public String description() {
        return "Derive the stage role of inquiries filed before the pipeline became a catalog";
    }

    @Override
    public void migrate(MigrationContext context) {
        String inquiries = context.registry().getDocumentDescriptor(LeadInquiry.class).tableName();
        for (Map.Entry<String, StageRole> entry : ROLES.entrySet()) {
            context.handle()
                    .createUpdate("UPDATE " + inquiries + " SET stage_role = :role "
                            + "WHERE stage_role IS NULL AND stage = :stage")
                    .bind("role", EnumerationPersistence.resolveId(StageRole.class, entry.getValue()))
                    .bind("stage", legacyId(entry.getKey()))
                    .execute();
        }
        // A lead filed into no stage at all starts where a new one does.
        context.handle()
                .createUpdate("UPDATE " + inquiries + " SET stage_role = :role WHERE stage_role IS NULL")
                .bind("role", EnumerationPersistence.resolveId(StageRole.class, StageRole.NEW))
                .execute();
    }

    /** The id the enumeration gave a stage: a name-based UUID over {@code <enum class>.<constant>}. */
    private static UUID legacyId(String constant) {
        return UUID.nameUUIDFromBytes(
                ("com.weddingplanner.crm.domain.LeadStage." + constant).getBytes(StandardCharsets.UTF_8));
    }
}
