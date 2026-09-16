package com.weddingplanner.crm.events.domain;
import su.onno.annotations.*;
import su.onno.model.CatalogObject;
@Catalog(name="BudgetCategories",title="Budget categories",codePrefix="BC-",context="Events")
@AccessControl(readRoles={"MANAGER","ADMIN"},writeRoles={"MANAGER","ADMIN"})
public class BudgetCategory extends CatalogObject {}
