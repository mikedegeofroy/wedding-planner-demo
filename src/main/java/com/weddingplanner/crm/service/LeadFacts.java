package com.weddingplanner.crm.service;

import com.weddingplanner.crm.domain.Contact;
import com.weddingplanner.crm.domain.LeadInquiry;
import com.weddingplanner.crm.repository.LeadInquiryRepository;
import su.onno.annotations.EnumLabel;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The small readings of an inquiry that more than one surface needs — the contact panel's fields and
 * the inbox's brief card both answer "which inquiry speaks for this couple, and what does it say".
 * Keeping them here is what stops the two from drifting into reporting different stages for the
 * same person.
 */
public final class LeadFacts {
    private LeadFacts() {}

    /**
     * Each couple's inquiry that speaks for them — the furthest along the pipeline, matching how
     * {@link LeadPipelineService#stageByContact} folders them, so an older lost inquiry never
     * speaks for a couple who has since booked.
     */
    public static Map<UUID, LeadInquiry> furthestByContact(LeadInquiryRepository leads,
            PipelineStageService stages) {
        var furthest = new HashMap<UUID, LeadInquiry>();
        for (LeadInquiry lead : leads.findAllActive()) {
            if (lead.getContact() == null) continue;
            furthest.merge(lead.getContact().id(), lead,
                    (current, incoming) -> stages.positionOf(incoming.getStage())
                            > stages.positionOf(current.getStage()) ? incoming : current);
        }
        return furthest;
    }

    /**
     * A contact's live inquiry, memoised for a second. The contact panel asks once per displayed
     * field and every answer comes from the same inquiry; without this, one panel read would scan
     * the inquiries ten times over. A stage moved a moment ago still shows on the next breath.
     */
    public static Function<Contact, Optional<LeadInquiry>> live(LeadInquiryRepository leads,
            PipelineStageService stages) {
        var cached = new AtomicReference<Map.Entry<Long, Map<UUID, LeadInquiry>>>();
        return contact -> {
            long now = System.nanoTime();
            var snapshot = cached.get();
            if (snapshot == null || now - snapshot.getKey() > 1_000_000_000L) {
                snapshot = Map.entry(now, furthestByContact(leads, stages));
                cached.set(snapshot);
            }
            return Optional.ofNullable(snapshot.getValue().get(contact.getId()));
        };
    }

    /** An enumeration constant's {@code @EnumLabel} text, or its name when it carries none. */
    public static String label(Enum<?> value) {
        EnumLabel label = annotation(value);
        return label == null ? (value == null ? null : value.name()) : label.value();
    }

    /** The same constant's {@code @EnumLabel} colour, so a badge reads as it does everywhere else. */
    public static String color(Enum<?> value) {
        EnumLabel label = annotation(value);
        return label == null || label.color().isBlank() ? null : label.color();
    }

    /** A wedding budget as people here write one: "€450,000", or nothing when it is not known yet. */
    public static String euros(BigDecimal amount) {
        return amount == null ? null : NumberFormat.getIntegerInstance(Locale.US).format(amount) + " €";
    }

    private static EnumLabel annotation(Enum<?> value) {
        if (value == null) return null;
        try {
            return value.getDeclaringClass().getField(value.name()).getAnnotation(EnumLabel.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
