package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.EventParty;
import org.springframework.stereotype.Component;
import su.onno.migration.AppMigration;
import su.onno.migration.MigrationContext;
import su.onno.repository.EnumerationPersistence;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Re-keys participant rows written while the role was its own {@code EventPartyRole} enumeration
 * onto the {@link InboxFolder} the contact card already carries.
 *
 * <p>An enumeration column stores the id of the value, and that id is derived from the enum's class
 * name as well as its constant name — so no value of the retired enum equals the folder that means
 * the same thing, and a row left alone would fail to resolve on the next read. The three old ids
 * are recomputed here from the retired class's name rather than read from its table, because the
 * schema upgrade that runs before this migration is free to drop a table no entity declares any
 * more.</p>
 */
@Component
public class EventPartyRoleMigration implements AppMigration {

    /** The enum whose values these rows were written with, named as it was on disk. */
    private static final String RETIRED_ENUM = "com.weddingplanner.crm.events.domain.EventPartyRole";

    private static final Map<String, InboxFolder> ROLES = new LinkedHashMap<>(Map.of(
            "CLIENT", InboxFolder.CLIENTS,
            "CONTRACTOR", InboxFolder.CONTRACTORS,
            "VENUE", InboxFolder.VENUES));

    @Override
    public String version() {
        return "2026.09.16.1";
    }

    @Override
    public String description() {
        return "Re-key event participant roles onto the contact inbox folder";
    }

    @Override
    public void migrate(MigrationContext context) {
        String parties = context.registry().getCatalogDescriptor(EventParty.class).tableName();
        ROLES.forEach((retired, folder) -> context.handle()
                .createUpdate("UPDATE " + parties + " SET role = :folder WHERE role = :retired")
                .bind("folder", EnumerationPersistence.resolveId(InboxFolder.class, folder))
                .bind("retired", retiredId(retired))
                .execute());
        // A row whose role survives as neither (hand-written, or from a value this map never knew)
        // would still fail to resolve and take the whole participants list down with it. Contractor
        // is what a new row defaults to and the only kind that carries a commission, so it is the
        // safe reading of an unknown.
        context.handle()
                .createUpdate("UPDATE " + parties + " SET role = :folder WHERE role IS NULL OR role NOT IN ("
                        + "SELECT _id FROM " + context.registry().getEnumerationDescriptor(InboxFolder.class).tableName() + ")")
                .bind("folder", EnumerationPersistence.resolveId(InboxFolder.class, InboxFolder.CONTRACTORS))
                .execute();
    }

    /** The id the retired enumeration gave one of its constants: md5 of {@code class.CONSTANT}. */
    private static UUID retiredId(String constant) {
        return UUID.nameUUIDFromBytes((RETIRED_ENUM + "." + constant).getBytes(StandardCharsets.UTF_8));
    }
}
