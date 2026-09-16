package com.weddingplanner.crm.domain;

import su.onno.annotations.EnumLabel;
import su.onno.annotations.Enumeration;

@Enumeration(name = "LeadChannels", title = "Lead channel")
public enum LeadChannel {
    @EnumLabel(value = "Instagram", color = "#E1306C") INSTAGRAM,
    @EnumLabel(value = "WhatsApp", color = "#25D366") WHATSAPP,
    @EnumLabel(value = "Website form", color = "#8B5CF6") WEBSITE_FORM,
    @EnumLabel(value = "Email", color = "#2563EB") EMAIL
}
