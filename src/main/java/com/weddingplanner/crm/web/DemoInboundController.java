package com.weddingplanner.crm.web;

import com.weddingplanner.crm.service.InboundTriageService;
import com.weddingplanner.crm.service.InboundTriageService.Inbound;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets someone outside the app play the part of a stranger writing in — from a terminal, a phone
 * shortcut or a second laptop — so the demo shows a message arriving rather than one already there.
 *
 * <p>Deliberately outside {@code /api/**}: onno's auth guards that prefix with a session, and a curl
 * from the presenter's terminal has none. This endpoint guards itself instead:
 * {@code planner.demo-hooks.open=true} lets anyone who can reach the app call it (a phone on the same
 * Wi-Fi, for a live demo); otherwise {@code planner.demo-hooks.token} must be sent as
 * {@code X-Demo-Token}, or, with no token set, only this machine may call it.
 * {@code planner.demo-hooks.enabled=false} removes it entirely.</p>
 */
@RestController
@RequestMapping("/demo-hooks")
public class DemoInboundController {

    private final InboundTriageService triage;
    private final boolean enabled;
    private final boolean open;
    private final String token;

    public DemoInboundController(InboundTriageService triage,
                                 @Value("${planner.demo-hooks.enabled:true}") boolean enabled,
                                 @Value("${planner.demo-hooks.open:false}") boolean open,
                                 @Value("${planner.demo-hooks.token:}") String token) {
        this.triage = triage;
        this.enabled = enabled;
        this.open = open;
        this.token = token == null ? "" : token.strip();
    }

    /** The ready-made senders, so the script can list them. */
    @GetMapping("/scenarios")
    public Map<String, Inbound> scenarios(HttpServletRequest request,
                                          @RequestHeader(value = "X-Demo-Token", required = false) String given) {
        guard(request, given);
        return InboundTriageService.SCENARIOS;
    }

    /**
     * {@code {"scenario":"couple-como"}} sends a ready-made message; otherwise {@code name},
     * {@code channel} (instagram | whatsapp | email) and {@code message} describe your own.
     * {@code pauseMs} is how long the chat sits unsorted before Jev files it (default 1500).
     */
    @PostMapping("/inbound")
    public Map<String, Object> inbound(HttpServletRequest request,
                                       @RequestHeader(value = "X-Demo-Token", required = false) String given,
                                       @RequestBody Map<String, Object> body) {
        guard(request, given);
        Inbound inbound;
        Object scenario = body.get("scenario");
        if (scenario != null) {
            inbound = InboundTriageService.SCENARIOS.get(scenario.toString());
            if (inbound == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown scenario. Try one of " + InboundTriageService.SCENARIOS.keySet());
        } else {
            String message = text(body, "message");
            if (message == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Send {\"scenario\": …} or at least {\"message\": …}");
            if (message.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is too long");
            String name = text(body, "name");
            inbound = new Inbound(name == null ? "New contact" : name, text(body, "channel"), message,
                    text(body, "email"), text(body, "phone"));
        }
        long pause = body.get("pauseMs") instanceof Number n ? n.longValue() : 1500;

        var outcome = triage.receive(inbound, pause);
        var r = outcome.classification();
        var result = new LinkedHashMap<String, Object>();
        result.put("from", inbound.name());
        result.put("engine", r.live() ? r.model() : "keyword rules (offline)");
        result.put("latencyMs", r.millis());
        result.put("sender", r.sender().choice());
        result.put("senderProbability", r.sender().probability());
        result.put("confidence", r.senderConfidence());
        result.put("segment", r.band().choice());
        result.put("segmentProbability", r.band().probability());
        result.put("storedBand", outcome.band().name());
        result.put("budget", r.budget());
        result.put("guests", r.guests());
        result.put("urgency", r.urgency());
        result.put("routedTo", outcome.folder().label());
        result.put("conversationId", outcome.conversationId());
        result.put("inquiryId", outcome.inquiryId());
        return result;
    }

    private void guard(HttpServletRequest request, String given) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (open) return;
        if (!token.isEmpty()) {
            boolean ok = given != null && MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), given.strip().getBytes(StandardCharsets.UTF_8));
            if (!ok) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or wrong X-Demo-Token");
            return;
        }
        // A request relayed by a proxy on this host also arrives from loopback; the forwarding
        // headers are what tell it apart from the presenter's own terminal.
        boolean proxied = request.getHeader("X-Forwarded-For") != null || request.getHeader("Forwarded") != null
                || request.getHeader("X-Real-IP") != null;
        try {
            if (proxied || !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Set planner.demo-hooks.token (PLANNER_DEMO_HOOK_TOKEN) to call this from another machine");
        } catch (java.net.UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }
}
