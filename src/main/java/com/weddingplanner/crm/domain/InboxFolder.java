package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * Who the counterparty of a conversation is. The Conversations inbox folds by this, so each value
 * carries the two things a folder is recognised by before its label is read: a glyph and a hue.
 *
 * <p>Both describe the <em>relationship</em>, not the wording — a deployment that renames "Venues"
 * to "Locations", or runs the app in another language, keeps the landmark and the sky blue it
 * already learned to scan for.</p>
 */
@Enumeration(name = "InboxFolders", title = "Inbox folder")
public enum InboxFolder {
    @EnumLabel(value = "Clients", color = "#E11D48") CLIENTS("Clients", "heart-handshake"),
    @EnumLabel(value = "Contractors", color = "#F59E0B") CONTRACTORS("Contractors", "hard-hat"),
    @EnumLabel(value = "Partners", color = "#6366F1") PARTNERS("Partners", "handshake"),
    @EnumLabel(value = "Venues", color = "#0EA5E9") VENUES("Venues", "landmark"),
    @EnumLabel(value = "Competitors", color = "#64748B") COMPETITORS("Competitors", "swords"),
    @EnumLabel(value = "Team", color = "#8B5CF6") TEAM("Team", "users"),
    /**
     * A first message nobody has sorted yet, or one the classifier would not commit to. It keeps a
     * new chat out of Clients until something has decided it belongs there.
     */
    @EnumLabel(value = "Needs review", color = "#F97316") UNSORTED("Needs review", "inbox");

    private final String label;
    private final String glyph;

    InboxFolder(String label, String glyph) { this.label = label; this.glyph = glyph; }

    public String label() { return label; }

    /** A lucide glyph name, as the shell's icon bridge takes them. An unknown one draws a circle. */
    public String glyph() { return glyph; }
}
