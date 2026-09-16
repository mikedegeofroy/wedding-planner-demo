package com.weddingplanner.crm.seed;

import com.weddingplanner.crm.service.PipelineStageService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The stage list has to exist before anything that files a lead into one. */
@Order(-100)
@Component
public class PipelineStageSeeder implements CommandLineRunner {
    private final PipelineStageService stages;

    public PipelineStageSeeder(PipelineStageService stages) {
        this.stages = stages;
    }

    @Override
    public void run(String... args) {
        stages.seedDefaults();
    }
}
