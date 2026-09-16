package com.weddingplanner.crm.domain;

import su.onno.annotations.Attribute;
import su.onno.model.TabularSectionRow;

import java.time.LocalDateTime;

/**
 * One recorded contact between a couple and the brand, in order. The rows carry their own source
 * and channel because a couple rarely arrives and converts through the same one: the first row is
 * the first-touch attribution and the last row bearing a marketing source is the last touch.
 */
public class LeadTouchpoint extends TabularSectionRow {

    @Attribute(displayName = "When", required = true)
    private LocalDateTime at;

    @Attribute(displayName = "Touch", required = true)
    private TouchType type = TouchType.INBOUND_MESSAGE;

    @Attribute(displayName = "Channel")
    private LeadChannel channel;

    @Attribute(displayName = "Marketing source")
    private MarketingSource source;

    @Attribute(displayName = "Campaign / detail", length = 300)
    private String detail;

    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
    public TouchType getType() { return type; }
    public void setType(TouchType type) { this.type = type; }
    public LeadChannel getChannel() { return channel; }
    public void setChannel(LeadChannel channel) { this.channel = channel; }
    public MarketingSource getSource() { return source; }
    public void setSource(MarketingSource source) { this.source = source; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
