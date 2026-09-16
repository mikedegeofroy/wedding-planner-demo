package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * One kind of contact between a couple and the brand. The inquiry's touch history is the answer to
 * "what contact has this client actually had with us" — an ad click and a venue visit are both
 * touches, but only one of them is engagement.
 */
@Enumeration(name = "TouchTypes", title = "Touch type")
public enum TouchType {
    @EnumLabel(value = "Ad click", color = "#0668E1") AD_CLICK,
    @EnumLabel(value = "Site visit", color = "#0EA5E9") SITE_VISIT,
    @EnumLabel(value = "Form submitted", color = "#8B5CF6") FORM_SUBMIT,
    @EnumLabel(value = "Inbound message", color = "#059669") INBOUND_MESSAGE,
    @EnumLabel(value = "Our reply", color = "#64748B") OUTBOUND_REPLY,
    @EnumLabel(value = "Phone call", color = "#D97706") CALL,
    @EnumLabel(value = "Video meeting", color = "#2563EB") VIDEO_MEETING,
    @EnumLabel(value = "Venue visit", color = "#C13584") VENUE_VISIT,
    @EnumLabel(value = "Proposal sent", color = "#7C3AED") PROPOSAL_SENT
}
