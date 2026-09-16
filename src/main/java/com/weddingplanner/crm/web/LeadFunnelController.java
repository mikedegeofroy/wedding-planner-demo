package com.weddingplanner.crm.web;

import com.weddingplanner.crm.service.LeadFunnelService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import su.onno.ui.UiAccessService;

import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * The funnel widget's one read. It carries the dashboard's own window as {@code from}/{@code to} —
 * the same period the tiles and charts beside it report on — so the board cannot show two different
 * periods at once. An optional {@code segment} narrows the ladder to one slice of {@code by} (one
 * channel's own funnel); the breakdown it is picked from still covers the whole period.
 *
 * <p>Reading it is reading the inquiries, so it is gated on exactly that: whoever may not read
 * {@code LeadInquiries} may not read their funnel either.</p>
 */
@RestController
@RequestMapping("/api/planner/funnel")
public class LeadFunnelController {

    private final LeadFunnelService funnels;
    private final UiAccessService access;

    public LeadFunnelController(LeadFunnelService funnels, UiAccessService access) {
        this.funnels = funnels;
        this.access = access;
    }

    /** The dimensions the widget offers in its "break down by" control, in the order it shows them. */
    public record Split(String key, String label) {}

    public record Response(LeadFunnelService.Funnel funnel, List<Split> splits) {}

    @GetMapping
    @Transactional(readOnly = true)
    public Response funnel(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String by, @RequestParam(required = false) String segment,
            Principal principal) {
        if (principal == null || !access.canRead(principal, "document", "LeadInquiries")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        var splits = Arrays.stream(LeadFunnelService.Dimension.values())
                .map(dimension -> new Split(dimension.name().toLowerCase(), dimension.label()))
                .toList();
        return new Response(funnels.funnel(moment(from, false), moment(to, true),
                LeadFunnelService.Dimension.of(by), segment), splits);
    }

    /**
     * A bound as the widget sends it: a local timestamp, a UTC instant, or a plain date. A date-only
     * end bound means the whole of that day, which is the reading a person picking "to 30 June"
     * expects; every other shape is taken literally.
     */
    private static LocalDateTime moment(String value, boolean end) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return LocalDateTime.parse(text);
        } catch (RuntimeException notALocalTimestamp) {
            // fall through
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(text), ZoneId.systemDefault());
        } catch (RuntimeException notAnInstant) {
            // fall through
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return (end ? date.plusDays(1) : date).atStartOfDay();
        } catch (RuntimeException notADate) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable date: " + value);
        }
    }
}
