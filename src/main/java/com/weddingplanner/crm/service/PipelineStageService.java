package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.PipelineStage;
import com.weddingplanner.crm.domain.PipelineStageLookup;
import com.weddingplanner.crm.domain.StageRole;
import com.weddingplanner.crm.repository.PipelineStageRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import su.onno.types.Ref;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The pipeline stage list: who owns it, what it currently is, and what the code may assume about it.
 *
 * <p>The list is the planning team's to edit, so every reader here goes through the catalog rather
 * than through a compiled constant. Reads are memoised for a second — one inbox refresh asks for the
 * stage list once per folder, once per pill and once per menu — while a stage renamed a moment ago
 * still shows on the next breath.</p>
 */
@Service
public class PipelineStageService implements PipelineStageLookup {
    /** Long enough to spare one screen refresh a scan per field; short enough that an edit shows. */
    private static final long CACHE_NANOS = 1_000_000_000L;

    /**
     * Label, colour and meaning of the stages this business started with.
     *
     * <p>Five, because a folder list is read at a glance and the seven it started with were not
     * five different decisions. "Qualifying", "Meeting booked" and "Proposal / contract" all
     * answered the same question — <em>is this couple worth our time, and are we still on it</em> —
     * and splitting that into three left three folders a planner had to keep re-sorting between
     * while the meeting and the proposal were already recorded as touchpoints and read off the
     * funnel from there.</p>
     *
     * <p>Losing, by contrast, really is two decisions, so it is two stages: a couple the business
     * wanted and did not win, and one it was never going to. See {@link StageRole#lost()}.</p>
     */
    private static final List<Default> DEFAULTS = List.of(
            new Default("NEW", "New", "#64748B", StageRole.NEW),
            new Default("QUALIFIED", "Qualified", "#0EA5E9", StageRole.QUALIFIED),
            new Default("BOOKED", "Booked", "#059669", StageRole.WON),
            new Default("LOST", "Lost — qualified", "#DC2626", StageRole.LOST),
            new Default("LOST_NOT_QUALIFIED", "Lost — not a fit", "#94A3B8", StageRole.DISQUALIFIED));

    private record Default(String legacyName, String label, String color, StageRole role) {}

    private final PipelineStageRepository stages;
    private final AtomicReference<Map.Entry<Long, List<PipelineStage>>> cached = new AtomicReference<>();

    public PipelineStageService(PipelineStageRepository stages) {
        this.stages = stages;
    }

    /** Domain write hooks have no injection; publish the live list to them. */
    @PostConstruct
    public void install() {
        PipelineStageLookup.Holder.install(this);
    }

    /**
     * Creates the stages this business started with, once.
     *
     * <p>Their ids are the ones the pipeline stage <em>enumeration</em> used to persist, so every
     * inquiry written before the list became editable still points at its own stage without a
     * backfill. A stage the team has since deleted stays deleted — the check deliberately sees
     * tombstones — and a renamed or recoloured one is left exactly as they left it.</p>
     */
    public void seedDefaults() {
        for (Default value : DEFAULTS) {
            UUID id = legacyId(value.legacyName());
            if (stages.findById(id).isPresent()) continue;
            var stage = new PipelineStage();
            stage.setId(id);
            stage.setDescription(value.label());
            stage.setColor(value.color());
            stage.setRole(value.role());
            stage.setPosition(DEFAULTS.indexOf(value) * 10);
            stages.save(stage);
        }
        invalidate();
    }

    /** Forget the memoised list; an edit made through this service shows on the next read. */
    public void invalidate() {
        cached.set(null);
    }

    /** The pipeline, first stage to last. */
    public List<PipelineStage> ordered() {
        long now = System.nanoTime();
        var snapshot = cached.get();
        if (snapshot == null || now - snapshot.getKey() > CACHE_NANOS) {
            snapshot = Map.entry(now, stages.findAllActive().stream()
                    .sorted(Comparator.comparing((PipelineStage stage) ->
                                    stage.getPosition() == null ? Integer.MAX_VALUE : stage.getPosition())
                            .thenComparing(stage -> stage.getDescription() == null ? "" : stage.getDescription()))
                    .toList());
            cached.set(snapshot);
        }
        return snapshot.getValue();
    }

    /** The stage with this id, whatever the team has since called it. */
    public Optional<PipelineStage> find(UUID id) {
        if (id == null) return Optional.empty();
        return ordered().stream().filter(stage -> stage.getId().equals(id)).findFirst();
    }

    public Optional<PipelineStage> find(Ref<PipelineStage> stage) {
        return stage == null ? Optional.empty() : find(stage.id());
    }

    /** The stage that plays this role, earliest in the pipeline when several claim it. */
    public Optional<PipelineStage> stageFor(StageRole role) {
        return ordered().stream().filter(stage -> stage.getRole() == role).findFirst();
    }

    /** The stage a lead moves to next, or empty at the end of the pipeline and in a terminal stage. */
    public Optional<PipelineStage> next(PipelineStage stage) {
        if (stage == null || stage.getRole() == StageRole.WON || stage.getRole().lost()) {
            return Optional.empty();
        }
        List<PipelineStage> pipeline = ordered();
        int index = pipeline.indexOf(stage);
        if (index < 0) {
            index = indexOfId(pipeline, stage.getId());
        }
        // Either loss is somewhere in the list but is never the forward step out of an open stage.
        return index < 0 ? Optional.empty() : pipeline.stream().skip(index + 1L)
                .filter(candidate -> !candidate.getRole().lost()).findFirst();
    }

    /** The stage's name as the team writes it today. */
    public String label(Ref<PipelineStage> stage) {
        return find(stage).map(PipelineStage::getDescription).orElse(null);
    }

    /** The stage's colour, so a pill reads the same everywhere it appears. */
    public String color(Ref<PipelineStage> stage) {
        return find(stage).map(PipelineStage::getColor).filter(color -> !color.isBlank()).orElse(null);
    }

    @Override
    public Optional<Ref<PipelineStage>> byRole(StageRole role) {
        return stageFor(role).map(stage -> Ref.of(PipelineStage.class, stage.getId()));
    }

    @Override
    public StageRole roleOf(Ref<PipelineStage> stage) {
        return find(stage).map(PipelineStage::getRole).orElse(StageRole.CUSTOM);
    }

    @Override
    public int positionOf(Ref<PipelineStage> stage) {
        return find(stage).map(value -> value.getPosition() == null ? Integer.MAX_VALUE : value.getPosition())
                .orElse(Integer.MAX_VALUE);
    }

    private static int indexOfId(List<PipelineStage> pipeline, UUID id) {
        for (int index = 0; index < pipeline.size(); index++) {
            if (pipeline.get(index).getId().equals(id)) return index;
        }
        return -1;
    }

    /**
     * The id the framework gave a pipeline stage while the list was an enumeration: a name-based
     * UUID over {@code <enum class>.<constant>}. Spelled out rather than derived from the deleted
     * enum so the inquiries written back then keep resolving.
     */
    public static UUID legacyId(String constant) {
        return UUID.nameUUIDFromBytes(
                ("com.weddingplanner.crm.domain.LeadStage." + constant).getBytes(StandardCharsets.UTF_8));
    }
}
