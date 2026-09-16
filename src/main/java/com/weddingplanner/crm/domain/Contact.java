package com.weddingplanner.crm.domain;

import su.onno.annotations.*;
import su.onno.model.CatalogObject;
import su.onno.types.Ref;

/**
 * The host contact catalog shared by inquiries and the CRM inbox. One card is always one person:
 * a wedding is sold to a couple, but the couple is two people with their own phone, inbox and
 * opinions, and merging them into a single "Amelia &amp; Noah" card makes every message, party row
 * and invoice ambiguous about who it belongs to. {@link #partner} carries the couple relation
 * instead, so the pair stays visible without collapsing the two identities.
 */
@Catalog(name = "Contacts", title = "Contacts", codePrefix = "CT-", context = "Sales")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"MANAGER", "ADMIN"})
public class Contact extends CatalogObject {
    @Attribute(displayName = "Email", email = true, length = 200)
    private String email;
    @Attribute(displayName = "Phone", length = 80)
    private String phone;
    @Attribute(displayName = "Inbox folder")
    private InboxFolder inboxFolder = InboxFolder.CLIENTS;
    /** The other half of the couple, or a family member this person is planning alongside. */
    @Attribute(displayName = "Partner")
    private Ref<Contact> partner;
    /**
     * The share of this supplier's own invoices they rebate to Wedding Planner, as a percentage. It is the
     * standing deal with the supplier, not a term of any one wedding — a single event may override
     * it on its participant row. Meaningless on a client card and left blank there.
     */
    @Attribute(displayName = "Commission to the planner, %", precision = 5, scale = 2, min = 0, max = 100)
    private java.math.BigDecimal commissionRate;
    public InboxFolder getInboxFolder() { return inboxFolder; }
    public void setInboxFolder(InboxFolder inboxFolder) { this.inboxFolder = inboxFolder; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Ref<Contact> getPartner() { return partner; }
    public void setPartner(Ref<Contact> partner) { this.partner = partner; }
    public java.math.BigDecimal getCommissionRate() { return commissionRate; }
    public void setCommissionRate(java.math.BigDecimal commissionRate) { this.commissionRate = commissionRate; }
}
