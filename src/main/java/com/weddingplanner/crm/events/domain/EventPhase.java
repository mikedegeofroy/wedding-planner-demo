package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
@Enumeration(name="EventPhases",title="EventPhase")
public enum EventPhase {
@EnumLabel("Wedding day") WEDDING,
@EnumLabel("Welcome / rehearsal") WELCOME,
@EnumLabel("Brunch / after wedding") BRUNCH,
@EnumLabel("Planning & coordination") PLANNING
}
