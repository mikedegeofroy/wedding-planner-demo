package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "BudgetBands", title = "Budget band")
public enum BudgetBand {
    @EnumLabel(value = "Below €300k", color = "#94A3B8") BELOW_300K,
    @EnumLabel(value = "€300k–€499k", color = "#0EA5E9") FROM_300K_TO_499K,
    @EnumLabel(value = "€500k–€749k", color = "#7C3AED") FROM_500K_TO_749K,
    @EnumLabel(value = "€750k+", color = "#A855F7") FROM_750K,
    @EnumLabel(value = "Unknown", color = "#F59E0B") UNKNOWN
}
