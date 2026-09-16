package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "LeadOwners", title = "Owner")
public enum LeadOwner {
    @EnumLabel(value = "Founder", color = "#7C3AED") FOUNDER,
    @EnumLabel(value = "Lead manager", color = "#0EA5E9") MANAGER
}
