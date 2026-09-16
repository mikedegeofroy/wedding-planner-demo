package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
@Enumeration(name="ChargeKinds",title="ChargeKind")
public enum ChargeKind {
@EnumLabel("Service") SERVICE,
@EnumLabel("Agency fee") AGENCY_FEE,
@EnumLabel("Refundable deposit") DEPOSIT
}
