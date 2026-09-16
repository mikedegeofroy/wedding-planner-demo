package com.weddingplanner.crm.domain;

import su.onno.annotations.AccessControl;
import su.onno.annotations.Attribute;
import su.onno.annotations.Catalog;
import su.onno.model.CatalogObject;

/**
 * One stage of the client pipeline, as the planning team keeps it. This is a catalog rather than an
 * enumeration because the pipeline is theirs to run: they add a stage when the business grows one,
 * rename it when the wording changes, reorder it, and give it a colour. The inbox folders, the stage
 * pills, the move-to-stage menu and the pipeline report all read this list, so a change lands
 * everywhere at once.
 *
 * <p>{@code description} is the stage's name. {@link #position} orders the pipeline — first to last
 * — and {@link #role} is the only thing the code keys on; see {@link StageRole}.</p>
 */
@Catalog(name = "PipelineStages", title = "Pipeline stages", codePrefix = "PS-", context = "Sales")
@AccessControl(readRoles = {"MANAGER", "ADMIN"}, writeRoles = {"MANAGER", "ADMIN"})
public class PipelineStage extends CatalogObject {
    @Attribute(displayName = "Order", required = true)
    private Integer position = 0;

    @Attribute(displayName = "Colour", length = 20)
    private String color;

    @Attribute(displayName = "Means", required = true)
    private StageRole role = StageRole.CUSTOM;

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public StageRole getRole() { return role; }
    public void setRole(StageRole role) { this.role = role; }
}
