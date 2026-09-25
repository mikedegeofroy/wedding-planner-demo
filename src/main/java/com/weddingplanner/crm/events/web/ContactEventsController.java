package com.weddingplanner.crm.events.web;

import com.weddingplanner.crm.domain.InboxFolder;
import com.weddingplanner.crm.events.domain.EventProject;
import com.weddingplanner.crm.events.repository.EventPartyRepository;
import com.weddingplanner.crm.events.repository.EventProjectRepository;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.UiAccessService;

/**
 * The weddings a contact is the client of, so a client's chat can offer the event instead of the
 * contact card. A contact is a client of an event when it is the event's primary client, or when it
 * sits on the event's party list as a client — the other half of the couple, most often.
 *
 * <p>Ordered with the next wedding first and past ones after it, because the chat's button opens
 * the first entry: a couple with a wedding next spring and one they held years ago is asking about
 * the one ahead.</p>
 */
@RestController
@RequestMapping("/api/planner/contacts")
public class ContactEventsController {

    private final EventProjectRepository events;
    private final EventPartyRepository parties;
    private final UiAccessService access;

    public ContactEventsController(EventProjectRepository events, EventPartyRepository parties, UiAccessService access) {
        this.events = events; this.parties = parties; this.access = access;
    }

    @GetMapping("/{id}/events") @Transactional(readOnly = true)
    public List<Map<String, Object>> events(@PathVariable UUID id, Principal principal) {
        if (principal == null || !access.canRead(principal, "catalog", "EventProjects"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var ids = new HashSet<UUID>();
        for (var party : parties.findAllActive())
            if (party.getRole() == InboxFolder.CLIENTS && party.getContact() != null && id.equals(party.getContact().id())
                    && party.getEvent() != null)
                ids.add(party.getEvent().id());
        LocalDate today = LocalDate.now();
        return events.findAllActive().stream()
                .filter(e -> ids.contains(e.getId()) || (e.getClient() != null && id.equals(e.getClient().id())))
                .sorted(Comparator.<EventProject, Boolean>comparing(e -> e.getStartDate() == null || e.getStartDate().isBefore(today))
                        .thenComparing(e -> e.getStartDate(), Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", e.getId());
                    row.put("name", e.getDescription());
                    row.put("startDate", e.getStartDate());
                    return (Map<String, Object>) row;
                })
                .toList();
    }
}
