package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
@Enumeration(name="BudgetPriceStates",title="BudgetPriceState")
public enum BudgetPriceState {
@EnumLabel("Quoted") QUOTED,
@EnumLabel("To be confirmed") TBD,
@EnumLabel("Included") INCLUDED,
@EnumLabel("Not required") NOT_REQUIRED
}
