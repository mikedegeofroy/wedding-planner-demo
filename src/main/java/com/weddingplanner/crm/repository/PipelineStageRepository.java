package com.weddingplanner.crm.repository;
import com.weddingplanner.crm.domain.PipelineStage;
import org.springframework.stereotype.Repository;
import su.onno.repository.CatalogRepository;
@Repository
public interface PipelineStageRepository extends CatalogRepository<PipelineStage> {}
