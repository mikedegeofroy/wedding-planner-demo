package com.weddingplanner.crm.web;

import com.weddingplanner.crm.service.ContactMergeService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.UiAccessService;

/**
 * The reviewed contact merge behind the contacts list selection: one call describes where the
 * selected cards disagree, a second applies the reviewer's choices. Both are gated on contact
 * write access; {@link ContactMergeService} re-checks every card individually.
 */
@RestController
@RequestMapping("/api/planner/contacts/merge")
public class ContactMergeController {
    /** A selection larger than this is a mis-click, not a merge worth reviewing field by field. */
    private static final int MAX_SELECTION = 20;

    private final ContactMergeService merges;
    private final UiAccessService access;
    private final boolean readOnly;

    public ContactMergeController(ContactMergeService merges, UiAccessService access,
            @Value("${onno.ui.read-only:false}") boolean readOnly) {
        this.merges=merges; this.access=access; this.readOnly=readOnly;
    }

    private void authorize(Principal principal) {
        if (readOnly || principal == null || !access.canWrite(principal, "catalog", "Contacts"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Contact write access required");
    }

    private List<UUID> selection(List<UUID> ids) {
        if (ids == null || ids.size() < 2)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Select at least two contacts to merge");
        if (ids.size() > MAX_SELECTION)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Merge at most " + MAX_SELECTION + " contacts at a time");
        return ids;
    }

    public record PreviewInput(List<UUID> ids) {}

    /** Read-only: what the selected cards hold and which fields need a decision. */
    @PostMapping("/preview")
    public ContactMergeService.Preview preview(@RequestBody PreviewInput input, Principal principal) {
        authorize(principal);
        try {
            return merges.preview(selection(input.ids()), principal);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    public record MergeInput(List<UUID> ids, UUID targetId, Map<String,String> keep) {}
    public record MergeResult(UUID keptId, int merged) {}

    /** Fold every selected card into {@code targetId}, keeping the reviewed values. */
    @PostMapping
    public MergeResult merge(@RequestBody MergeInput input, Principal principal) {
        authorize(principal);
        var ids = selection(input.ids());
        if (input.targetId() == null || !ids.contains(input.targetId()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Choose which of the selected contacts to keep");
        var sources = ids.stream().filter(id -> !id.equals(input.targetId())).distinct().toList();
        try {
            merges.merge(sources, input.targetId(), input.keep() == null ? Map.of() : input.keep(), principal);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
        return new MergeResult(input.targetId(), sources.size());
    }
}
