package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import org.springframework.stereotype.Component;
import su.onno.migration.AppMigration;
import su.onno.migration.MigrationContext;
import su.onno.repository.EnumerationPersistence;

/**
 * Contacts written before the inbox folder existed carry a null folder, and the CRM config reads
 * that null as {@link InboxFolder#CLIENTS}. The Clients page is a real feed constraint, not a
 * null-tolerant read, so the convention has to exist in the column too — otherwise every legacy
 * couple falls out of its own list.
 */
@Component
public class ContactFolderMigration implements AppMigration {

    @Override
    public String version() {
        return "2026.09.11.1";
    }

    @Override
    public String description() {
        return "Default contacts with no inbox folder to Clients";
    }

    @Override
    public void migrate(MigrationContext context) {
        String contacts = context.registry().getCatalogDescriptor(Contact.class).tableName();
        context.handle()
                .createUpdate("UPDATE " + contacts + " SET inbox_folder = :folder WHERE inbox_folder IS NULL")
                .bind("folder", EnumerationPersistence.resolveId(InboxFolder.class, InboxFolder.CLIENTS))
                .execute();
    }
}
