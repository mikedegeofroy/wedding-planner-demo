package com.weddingplanner.crm.events.repository;
import org.springframework.stereotype.Repository;
import su.onno.repository.CatalogRepository;
import com.weddingplanner.crm.events.domain.EventParty;
@Repository public interface EventPartyRepository extends CatalogRepository<EventParty> {}
