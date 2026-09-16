package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
@Enumeration(name="InvoiceDirections",title="InvoiceDirection")
public enum InvoiceDirection {
@EnumLabel("Invoice to client") CLIENT,
@EnumLabel("Invoice from contractor") SUPPLIER
}
