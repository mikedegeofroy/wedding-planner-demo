package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
@Enumeration(name="EventStages",title="EventStage")
public enum EventStage {
@EnumLabel(value="Planning",color="#F59E0B") PLANNING,
@EnumLabel(value="Confirmed",color="#059669") CONFIRMED,
@EnumLabel(value="Completed",color="#0EA5E9") COMPLETED,
@EnumLabel(value="Cancelled",color="#DC2626") CANCELLED
}
