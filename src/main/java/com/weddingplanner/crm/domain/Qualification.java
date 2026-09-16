package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "LeadQualifications", title = "Qualification")
public enum Qualification {
    @EnumLabel(value = "Needs information", color = "#F59E0B") NEEDS_INFORMATION,
    @EnumLabel(value = "Target", color = "#0EA5E9") TARGET,
    @EnumLabel(value = "VIP", color = "#7C3AED") VIP,
    @EnumLabel(value = "Not a fit", color = "#94A3B8") NOT_A_FIT
}
