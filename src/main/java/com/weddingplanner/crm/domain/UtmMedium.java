package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

/**
 * The {@code utm_medium} of the touch that produced the inquiry — the standard analytics split
 * between paid, owned and earned traffic. Source answers "which property", medium answers "which
 * kind of spend", so cost per lead is comparable across sources that share a medium.
 */
@Enumeration(name = "UtmMediums", title = "UTM medium")
public enum UtmMedium {
    @EnumLabel(value = "cpc (paid search)", color = "#34A853") CPC,
    @EnumLabel(value = "paid_social", color = "#0668E1") PAID_SOCIAL,
    @EnumLabel(value = "organic_social", color = "#C13584") ORGANIC_SOCIAL,
    @EnumLabel(value = "organic (search)", color = "#0EA5E9") ORGANIC_SEARCH,
    @EnumLabel(value = "referral", color = "#D97706") REFERRAL,
    @EnumLabel(value = "pr", color = "#7C3AED") PR,
    @EnumLabel(value = "email", color = "#2563EB") EMAIL,
    @EnumLabel(value = "(none) / direct", color = "#64748B") NONE
}
