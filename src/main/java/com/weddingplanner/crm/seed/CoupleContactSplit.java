package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.service.CoupleNames;
import com.weddingplanner.crm.service.InquiryInboxService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.types.Ref;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Splits client cards written before contacts were people — one card per couple, described as
 * "Amelia &amp; Noah" — into a card per person, linked by {@link Contact#getPartner()}.
 *
 * <p>The first person named keeps the original card, so every conversation, inquiry, event party
 * and invoice that already points at it stays pointed at a live contact; the rest are inserted
 * beside it at the ids a fresh import would have given them. Nothing is deleted, and a card whose
 * description no longer names two people is left alone — which is what makes a re-run a no-op.</p>
 */
// After InquiryInboxBackfill(10) imports, before EventCrewSeeder(30) reads the client list.
@Order(15)
@Component
public class CoupleContactSplit implements CommandLineRunner {

    private final ContactRepository contacts;

    public CoupleContactSplit(ContactRepository contacts) {
        this.contacts = contacts;
    }

    @Override
    public void run(String... args) {
        for (Contact card : contacts.findAllActive()) {
            if (card.getInboxFolder() != null && card.getInboxFolder() != InboxFolder.CLIENTS) continue;
            var people = CoupleNames.split(card.getDescription());
            if (people.size() < 2) continue;
            split(card, people);
        }
    }

    private void split(Contact card, java.util.List<String> people) {
        var household = new ArrayList<Contact>();
        card.setDescription(people.getFirst());
        household.add(card);
        for (int i = 1; i < people.size(); i++) {
            UUID id = InquiryInboxService.partnerId(card.getId(), i);
            // Include tombstones: a partner card the user deleted must not come back on restart.
            var existing = contacts.findById(id);
            if (existing.isPresent()) {
                if (existing.get().isDeletionMark()) continue;
                household.add(existing.get());
                continue;
            }
            var person = new Contact();
            person.setId(id);
            person.setDescription(people.get(i));
            // Email and phone stay with the person whose message arrived; the partner's own
            // details are not known from the inquiry and are filled in by the planning team.
            person.setInboxFolder(InboxFolder.CLIENTS);
            household.add(person);
        }
        for (int i = 0; i < household.size(); i++) {
            Contact other = household.size() == 2 ? household.get(1 - i) : (i == 0 ? null : household.getFirst());
            if (other != null) household.get(i).setPartner(Ref.of(Contact.class, other.getId()));
        }
        for (Contact person : household) contacts.save(person);
    }
}
