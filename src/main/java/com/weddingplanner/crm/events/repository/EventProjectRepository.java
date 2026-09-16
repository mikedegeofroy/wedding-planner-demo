package com.weddingplanner.crm.events.repository;
import org.springframework.stereotype.Repository;
import su.onno.repository.CatalogRepository;
import com.weddingplanner.crm.events.domain.EventProject;
@Repository public interface EventProjectRepository extends CatalogRepository<EventProject> {}
