package com.weddingplanner.crm.ui;
import com.weddingplanner.crm.domain.Contact;
import org.springframework.stereotype.Component;
import su.onno.ui.*;
@Component
public class ContactView implements EntityView<Contact> {
    private final com.weddingplanner.crm.service.ContactMergeService merges;
    public ContactView(com.weddingplanner.crm.service.ContactMergeService merges) { this.merges=merges; }
    public void actions(ActionSpec actions) {
        actions.action("mergeContact").label("Merge another contact into this one").icon("merge")
            .scope(ActionScope.DETAIL).roles("MANAGER", "ADMIN")
            .form(form -> form.input("source").label("Contact to combine with this card")
                .reference(Contact.class).required())
            .handler(context -> {
                merges.merge(java.util.UUID.fromString(context.input("source")), context.id(),
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
                return ActionResult.refresh(ActionToast.success("Contacts merged. All channels are available in this card's conversation."));
            });
    }
    public Class<Contact> entity() { return Contact.class; }
    public void list(ListSpec<Contact> list) {
        // Duplicate cards are the routine reason to pick several contacts at once, so this list
        // shows the selection checkboxes rather than leaving the selection to ⌘/Shift-click.
        list.columns(Contact::getDescription, Contact::getEmail, Contact::getPhone)
            .label(Contact::getDescription, "Name")
            .selectionCheckboxes(true)
            // The reviewed merge lives with the other selection commands (Open, Delete) rather than
            // beside the title, and opens its own dialog — the widget is registered under this type.
            .selectionWidget("plannerContactMerge");
    }
    public void fields(EntityConfigBuilder<Contact> fields) {
        fields.field(Contact::getDescription).label("Name").order(0).width("full");
        fields.field(Contact::getEmail).order(10).width("half");
        fields.field(Contact::getPhone).order(20).width("half");
        fields.field(Contact::getInboxFolder).order(30).width("half");
        // A card is one person; the couple (or family) relation lives here rather than in a name
        // like "Amelia & Noah", which no longer says whose phone number the card carries.
        fields.refField(Contact::getPartner).refSecondary(Contact::getPhone).order(40).width("half")
            .hint("The other half of the couple, or the family member planning alongside them");
        // A supplier's standing rebate. It is left blank on a couple's card, so it is not shown in
        // the list — most contacts in this CRM are clients, and an empty column would be noise.
        fields.field(Contact::getCommissionRate).order(50).width("half").hideInList()
            .placeholder("10")
            .hint("Supplier rebate to the planner on their own invoices. An event can agree its own rate.");
    }
}
