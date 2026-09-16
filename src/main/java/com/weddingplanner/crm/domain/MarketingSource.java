package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "MarketingSources", title = "Marketing source")
public enum MarketingSource {
    @EnumLabel(value = "Meta Ads", color = "#0668E1") META_ADS,
    @EnumLabel(value = "Google Ads", color = "#34A853") GOOGLE_ADS,
    @EnumLabel(value = "Organic Instagram", color = "#C13584") ORGANIC_INSTAGRAM,
    @EnumLabel(value = "Referral", color = "#D97706") REFERRAL,
    @EnumLabel(value = "Press", color = "#7C3AED") PRESS,
    @EnumLabel(value = "Direct / unknown", color = "#64748B") DIRECT
}
