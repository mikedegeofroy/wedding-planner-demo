package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * Speed to lead — how long the couple waited for the first human reply. Banded rather than raw
 * minutes because the reporting question is "what share of leads were answered inside the hour",
 * and because the industry benchmark itself is a band, not a number.
 */
@Enumeration(name = "ResponseBands", title = "Speed to lead")
public enum ResponseBand {
    @EnumLabel(value = "Under 5 min", color = "#059669") UNDER_5_MIN,
    @EnumLabel(value = "Under 1 hour", color = "#0EA5E9") UNDER_1_HOUR,
    @EnumLabel(value = "Under 24 hours", color = "#F59E0B") UNDER_24_HOURS,
    @EnumLabel(value = "Over 24 hours", color = "#DC2626") OVER_24_HOURS,
    @EnumLabel(value = "No reply yet", color = "#94A3B8") NO_REPLY
}
