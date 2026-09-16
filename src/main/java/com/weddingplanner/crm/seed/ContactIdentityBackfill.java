package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.repository.ContactRepository;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import com.weddingplanner.crm.service.InquiryInboxService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.crm.repository.ConversationRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gives the demo's client cards a family name and an address of their own.
 *
 * <p>The first demo generated a couple as two first names and filed the inquiry's own
 * {@code coupleN@example.com} against whichever of them wrote in. Fifty-one people then shared the
 * card name "William", and every reference picker that offers contacts — the event participant row
 * above all — opened on a page of identical lines with nothing to choose between them. A card is
 * how a person is recognised, so the fix belongs on the card: both halves of a couple take the
 * household's family name, and each gets the address that name implies.</p>
 *
 * <p>Only the demo's own generated values are rewritten — a blank field, or the
 * {@code coupleN@example.com} placeholder the seeder itself wrote. A name already carrying a second
 * word, and any address a user typed, are left exactly as they are, which is also what makes a
 * restart a no-op.</p>
 */
// After CoupleContactSplit(15): a household is two cards by then, and both take the same name.
@Order(16)
@Component
public class ContactIdentityBackfill implements CommandLineRunner {

    /**
     * Enough family names that the twenty-five first names the lead seeder draws from stop
     * colliding: a card is identified by the pair, and a page of the picker holds about twenty.
     */
    private static final String[] FAMILY_NAMES = {
            "Whitfield", "Rossi", "Marchetti", "Lindqvist", "Okafor", "Duval", "Ferretti", "Halloran",
            "Beaumont", "Castellani", "Nakamura", "Vasquez", "Moreau", "Sandberg", "Bellini", "Thornton",
            "Kowalski", "Alvarez", "Ferrante", "Hughes", "Lombardi", "Nilsen", "Okonkwo", "Pastore",
            "Quaranta", "Rinaldi", "Sinclair", "Tremblay", "Ueda", "Valente", "Weiss", "Ximenes",
            "Yilmaz", "Zanetti", "Ashford", "Barbieri", "Costa", "Dahl", "Esposito", "Fontaine",
            "Grimaldi", "Hartley", "Ibsen", "Jorgensen", "Keller", "Laurent", "Mancini", "Novak",
            "Oosterhuis", "Pellegrini", "Rasmussen", "Sorrentino", "Tavares", "Ullman", "Visconti",
            "Wagner", "Yates", "Zielinski", "Abbiati", "Brandt"};

    /**
     * The family name for the {@code block}-th run through the first names, so the lead seeder can
     * name a couple the same way this backfill does and a database built from empty needs no
     * backfill at all.
     */
    static String familyName(int block) {
        return FAMILY_NAMES[Math.floorMod(block, FAMILY_NAMES.length)];
    }

    /** The address the lead seeder writes when it has nothing better: safe to replace, never typed. */
    private static final String PLACEHOLDER_EMAIL = "^couple\\d+@example\\.com$";

    private final ContactRepository contacts;
    private final LeadInquiryRepository leads;
    private final ConversationRepository conversations;

    public ContactIdentityBackfill(ContactRepository contacts, LeadInquiryRepository leads,
            ConversationRepository conversations) {
        this.contacts = contacts;
        this.leads = leads;
        this.conversations = conversations;
    }

    @Override
    public void run(String... args) {
        var clients = contacts.findAllActive().stream()
                .filter(contact -> contact.getInboxFolder() == null || contact.getInboxFolder() == InboxFolder.CLIENTS)
                .toList();
        var byId = clients.stream().collect(Collectors.toMap(Contact::getId, client -> client));
        var taken = contacts.findAllActive().stream()
                .map(Contact::getEmail).filter(email -> email != null && !email.isBlank())
                .filter(email -> !email.matches(PLACEHOLDER_EMAIL))
                .map(email -> email.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(HashSet::new));

        var seen = new HashSet<UUID>();
        for (Contact client : clients) {
            if (!seen.add(client.getId())) continue;
            var household = new ArrayList<Contact>(List.of(client));
            Contact partner = client.getPartner() == null ? null : byId.get(client.getPartner().id());
            if (partner != null && seen.add(partner.getId())) household.add(partner);
            name(household, taken);
        }
        retellInquiries();
    }

    /**
     * One family name for the whole household, chosen from the card of the person who wrote in so
     * the two halves never drift apart and a restart lands on the same name again.
     */
    private void name(List<Contact> household, HashSet<String> taken) {
        Contact head = household.getFirst();
        String family = FAMILY_NAMES[Math.floorMod(head.getId().getMostSignificantBits(), FAMILY_NAMES.length)];
        for (Contact person : household) {
            boolean changed = false;
            String name = person.getDescription() == null ? "" : person.getDescription().trim();
            if (!name.isEmpty() && !name.contains(" ")) {
                name = name + " " + family;
                person.setDescription(name);
                changed = true;
            }
            String email = person.getEmail();
            if ((email == null || email.isBlank() || email.matches(PLACEHOLDER_EMAIL)) && name.contains(" ")) {
                person.setEmail(address(name, taken));
                changed = true;
            }
            if (changed) contacts.save(person);
        }
    }

    /** {@code amelia.whitfield@example.com}, numbered only when two people really do share a name. */
    private String address(String name, HashSet<String> taken) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim().replace(' ', '.');
        String candidate = base + "@example.com";
        for (int i = 2; !taken.add(candidate); i++) {
            candidate = base + i + "@example.com";
        }
        return candidate;
    }

    /**
     * Say the couple's new name back to the inquiry they arrived on, and to the conversation it
     * opened. The inquiry is addressed to the pair, so it keeps the couple wording — but with the
     * family name on it, an import on a fresh database splits into exactly the cards this pass just
     * wrote, instead of two first names that would have to be backfilled all over again.
     */
    private void retellInquiries() {
        for (LeadInquiry lead : leads.findAllActive()) {
            if (lead.getContact() == null) continue;
            var household = EventBookSeeder.household(contacts, lead.getContact());
            if (household.isEmpty()) continue;
            String couple = couple(household);
            if (couple.isBlank() || couple.equals(lead.getCoupleName())) continue;
            String previous = lead.getCoupleName();
            lead.setCoupleName(couple);
            String email = household.getFirst().getEmail();
            if (email != null && (lead.getEmail() == null || lead.getEmail().matches(PLACEHOLDER_EMAIL))) {
                lead.setEmail(email);
            }
            leads.save(lead);
            retitle(lead, previous);
        }
    }

    /**
     * "Amelia &amp; Noah Whitfield" — the family name is carried once, by the last person named,
     * which is both how a couple writes it and what {@code CoupleNames} reads back.
     */
    private static String couple(List<Contact> household) {
        var people = household.stream().map(Contact::getDescription)
                .filter(name -> name != null && !name.isBlank()).toList();
        if (people.isEmpty()) return "";
        if (people.size() == 1) return people.getFirst();
        var parts = new ArrayList<String>();
        for (int i = 0; i < people.size() - 1; i++) {
            String name = people.get(i);
            int space = name.indexOf(' ');
            parts.add(space < 0 ? name : name.substring(0, space));
        }
        parts.add(people.getLast());
        return String.join(" & ", parts);
    }

    /** The inquiry's conversation is titled after the couple; retitle the one this pass renamed. */
    private void retitle(LeadInquiry lead, String previous) {
        UUID conversationId = InquiryInboxService.stableId("conversation", lead.getId().toString());
        conversations.findActiveById(conversationId).ifPresent(conversation -> {
            String stale = previous + " · wedding inquiry";
            if (!stale.equals(conversation.getSubject())) return;
            String subject = lead.getCoupleName() + " · wedding inquiry";
            conversation.setSubject(subject);
            conversation.setDescription(subject);
            conversations.save(conversation);
        });
    }
}
