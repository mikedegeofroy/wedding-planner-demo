package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.repository.ContactRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import su.onno.crm.domain.Channel;
import su.onno.crm.repository.ContactIdentityRepository;
import su.onno.crm.service.CrmContactService;

/**
 * Turns the contact details a lead arrived with into the channels they can be reached on.
 *
 * <p>Every card in this demo carried an email, and some a phone, yet the contact panel's
 * <i>Identities</i> list was empty for all of them: an address on a card is a field, and the CRM
 * routes on identities. So a couple who filled in the website form had contact details and no way
 * to be contacted, which is the whole complaint about treating a form as a channel.</p>
 *
 * <p>Each identity is written <b>unverified</b>, and the panel says so — "Manually entered ·
 * unverified" against the "Provider linked" of an identity a connector confirmed. That distinction
 * is the honest one here: a couple typed a number into a form, and nobody has yet proved it answers
 * on WhatsApp.</p>
 */
// After WebsiteFormChannels(17), which is where a form lead's phone number is captured.
@Order(18)
@Component
public class ContactChannelIdentities implements CommandLineRunner {

    private final ContactRepository contacts;
    private final ContactIdentityRepository identities;
    private final CrmContactService crm;

    public ContactChannelIdentities(ContactRepository contacts, ContactIdentityRepository identities,
            CrmContactService crm) {
        this.contacts = contacts; this.identities = identities; this.crm = crm;
    }

    @Override
    public void run(String... args) {
        for (Contact contact : contacts.findAllActive()) {
            var existing = identities.findByCustomerAndDeletionMarkFalse(contact.getId());
            link(contact, Channel.EMAIL, "planner-email", contact.getEmail(), existing);
            // A number typed into a form is two ways to reach someone and one claim about them: it
            // is certainly a phone, and it is probably also a WhatsApp. Both are offered, both
            // unverified, so the planner chooses rather than the demo guessing.
            link(contact, Channel.WHATSAPP, "planner-whatsapp", contact.getPhone(), existing);
            link(contact, Channel.PHONE, "planner-phone", contact.getPhone(), existing);
        }
    }

    private void link(Contact contact, String channel, String connection, String address,
            java.util.List<su.onno.crm.domain.ContactIdentity> existing) {
        if (address == null || address.isBlank()) return;
        String normalized = Channel.EMAIL.equals(channel)
                ? address.strip().toLowerCase(java.util.Locale.ROOT)
                : "+" + address.replaceAll("[^0-9]", "");
        if (existing.stream().anyMatch(identity -> channel.equals(identity.getChannel()))) return;
        crm.link(contact.getId(), channel, connection, normalized, address.strip(), false);
    }
}
