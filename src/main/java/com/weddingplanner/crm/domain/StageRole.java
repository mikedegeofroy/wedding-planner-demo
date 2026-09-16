package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * What a stage <em>means</em> to the code, as opposed to what it is called. The stage list itself is
 * a catalog the planning team edits — they add, rename, reorder and recolour stages — but a handful
 * of rules have to keep working across any such list: a lead in a won stage is booked, a lead in a
 * lost stage keeps its lost reason, and qualification advances a lead out of the early stages. Those
 * rules key off this role rather than off a stage's name, so renaming "Booked" to "Signed" changes
 * the wording and nothing else. Every other stage is {@link #CUSTOM}: an ordinary open stage.
 */
@Enumeration(name = "StageRoles", title = "Stage role")
public enum StageRole {
    @EnumLabel("Untouched") NEW,
    @EnumLabel("Being qualified") QUALIFYING,
    @EnumLabel("Qualified") QUALIFIED,
    @EnumLabel("Meeting held") MEETING,
    @EnumLabel("Proposal out") PROPOSAL,
    @EnumLabel("Won") WON,
    @EnumLabel("Lost") LOST,
    @EnumLabel("Lost, never qualified") DISQUALIFIED,
    @EnumLabel("Ordinary stage") CUSTOM;

    /**
     * Whether a lead in this stage is out of the pipeline for good. Two roles end that way and they
     * are not the same loss: {@link #LOST} is a couple the business wanted and did not win, and
     * {@link #DISQUALIFIED} is one it was never going to. Lumping them together is what makes a
     * loss rate unreadable — a channel delivering unqualified traffic and one losing good couples
     * to a competitor need opposite responses, and they score identically until these are split.
     */
    public boolean lost() { return this == LOST || this == DISQUALIFIED; }
}
