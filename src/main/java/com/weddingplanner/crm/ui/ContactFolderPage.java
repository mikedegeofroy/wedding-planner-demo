package com.weddingplanner.crm.ui;

import com.weddingplanner.crm.domain.InboxFolder;
import su.onno.repository.EnumerationPersistence;
import su.onno.ui.Page;
import su.onno.ui.PageBuilder;

/**
 * One inbox folder of the shared Contacts catalog on its own route. Clients and contractors
 * are the same catalog — they share identities, conversations and the merge action — but day to day
 * they are two different lists, so each folder gets a page instead of a filter the user has to
 * re-apply. The feed constraint is permanent: this route never shows another folder's cards.
 *
 * <p>The page renders bare: the sidebar link already names the folder, so a title/subtitle row
 * above the list repeats it and pushes the first card below the fold.</p>
 *
 * <p>Enum columns store the value's deterministic UUID, not its name, so the filter binds the id
 * resolved from the constant rather than the literal {@code 'CLIENTS'}.</p>
 */
abstract class ContactFolderPage implements Page {

    private final InboxFolder folder;

    ContactFolderPage(InboxFolder folder) {
        this.folder = folder;
    }

    @Override
    public void compose(PageBuilder page) {
        page.bare();
        page.widget("Contacts").type("plannerContactFolderList").width("full")
                .config("folder", EnumerationPersistence.resolveId(InboxFolder.class, folder).toString());
    }
}
