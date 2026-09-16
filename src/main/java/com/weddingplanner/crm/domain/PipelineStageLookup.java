package com.weddingplanner.crm.domain;

import su.onno.types.Ref;
import java.util.Optional;

/**
 * The stage list as the domain sees it.
 *
 * <p>{@link LeadInquiry#beforeWrite()} has to answer "which stage is the qualified one" while the
 * framework writes the row, and a lifecycle hook on a domain object gets no Spring injection. The
 * service that owns the catalog therefore publishes itself through {@link Holder} at startup. A lead
 * written before that happens — a plain unit test constructing one — keeps whatever stage it was
 * given instead of advancing, which is why every read here is null-tolerant.</p>
 */
public interface PipelineStageLookup {
    /** The stage that plays this role, lowest {@code position} first when several do. */
    Optional<Ref<PipelineStage>> byRole(StageRole role);

    /** What the given stage means to the code, or {@link StageRole#CUSTOM} for an unknown one. */
    StageRole roleOf(Ref<PipelineStage> stage);

    /** A stage's place in the pipeline; {@link Integer#MAX_VALUE} when it cannot be resolved. */
    int positionOf(Ref<PipelineStage> stage);

    /** Publishes the live lookup to the domain. Only the pipeline service installs one. */
    final class Holder {
        private static volatile PipelineStageLookup current;

        private Holder() {
        }

        public static void install(PipelineStageLookup lookup) {
            current = lookup;
        }

        /** The installed lookup, or null before the application context is up. */
        public static PipelineStageLookup current() {
            return current;
        }
    }
}
