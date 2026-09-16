package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.repository.ContactRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Which folder each contact belongs in, read once instead of once per conversation.
 *
 * <p>The inbox asks this question for every chat it lists, and for every folder it lists it in: the
 * role inbox alone asks it six times per conversation. Answered with a repository call each time it
 * is one database round trip per question, which is survivable on a hand-written demo and is not
 * survivable on a real one — a thousand conversations exhausted the connection pool and the feed
 * stopped responding rather than merely slowing down.
 *
 * <p>So the answer is a map, built in one read and memoised for a second, the same bargain
 * {@link PipelineStageService} strikes with the stage list: a single inbox refresh reuses one
 * snapshot, and a contact refiled a moment ago still moves on the next breath.
 */
@Service
public class ContactFolderIndex {

    /** Long enough to serve one screen refresh from a single read; short enough that an edit shows. */
    private static final long CACHE_NANOS = 1_000_000_000L;

    private final ContactRepository contacts;
    private final AtomicReference<Map.Entry<Long, Map<UUID, InboxFolder>>> cached = new AtomicReference<>();

    public ContactFolderIndex(ContactRepository contacts) {
        this.contacts = contacts;
    }

    /**
     * Rebuild on the next question. Called at the start of a feed computation, which is the point a
     * refiled contact has to be visible from: a card moved to Partners moves its conversation with
     * it, and waiting a second for that would be a bug someone would rightly report. Between the
     * refresh and the hundreds of questions that one feed read then asks, the snapshot stands.
     */
    public void refresh() {
        cached.set(null);
    }

    /** The folder this customer's card is filed in, or null when there is no live card. */
    public InboxFolder of(UUID customer) {
        return customer == null ? null : current().get(customer);
    }

    private Map<UUID, InboxFolder> current() {
        var snapshot = cached.get();
        long now = System.nanoTime();
        if (snapshot != null && now - snapshot.getKey() < CACHE_NANOS) return snapshot.getValue();

        var index = new HashMap<UUID, InboxFolder>();
        for (Contact contact : contacts.findAllActive()) {
            // A card with no folder is a client: that is what the schema migration decided for the
            // rows that predate the column, and the reader must not disagree with it.
            index.put(contact.getId(),
                    contact.getInboxFolder() == null ? InboxFolder.CLIENTS : contact.getInboxFolder());
        }
        cached.set(Map.entry(now, Map.copyOf(index)));
        return index;
    }
}
