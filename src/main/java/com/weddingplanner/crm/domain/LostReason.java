package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * Why a lead did not convert. Recorded per inquiry so loss can be read by source: a channel that
 * loses on budget is a targeting problem, one that loses to a competitor is a positioning problem,
 * and the two need opposite responses.
 */
@Enumeration(name = "LostReasons", title = "Lost reason")
public enum LostReason {
    @EnumLabel(value = "Below budget", color = "#94A3B8") BUDGET,
    @EnumLabel(value = "Date unavailable", color = "#F59E0B") DATE_UNAVAILABLE,
    @EnumLabel(value = "Chose a competitor", color = "#DC2626") COMPETITOR,
    @EnumLabel(value = "Went quiet", color = "#64748B") WENT_QUIET,
    @EnumLabel(value = "Postponed", color = "#0EA5E9") POSTPONED,
    @EnumLabel(value = "Out of scope", color = "#7C3AED") OUT_OF_SCOPE
}
