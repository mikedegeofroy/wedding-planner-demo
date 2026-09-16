package com.weddingplanner.crm.zoom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The planner's Zoom workspace: the account meetings are created under, and the creation call
 * itself.
 *
 * <p>{@link #schedule} stands in for Zoom's {@code POST /users/me/meetings}. It returns the same
 * fields that call returns, and it derives them from the request id so the operation is idempotent
 * — a retried submission yields the identical meeting number rather than a second meeting. Moving
 * to the live API is a change to this one method: sign the request with the workspace's OAuth
 * token, and map the response onto {@link ZoomMeeting}.</p>
 */
@Component
public class ZoomWorkspace {

    /** Passcode alphabet without the characters people mis-read aloud on a call: O/0, I/l/1. */
    private static final String PASSCODE = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final String email;
    private final String plan;
    private final String since;
    private final String host;
    private final String studio;

    public ZoomWorkspace(
            @Value("${planner.zoom.account-email:studio@weddingplanner.zoom}") String email,
            @Value("${planner.zoom.plan:Zoom Workplace Pro}") String plan,
            @Value("${planner.zoom.connected-since:March 2026}") String since,
            @Value("${planner.zoom.join-host:us06web.zoom.us}") String host,
            @Value("${planner.zoom.studio-name:Wedding Planner}") String studio) {
        this.email = email;
        this.plan = plan;
        this.since = since;
        this.host = host;
        this.studio = studio;
    }

    /** What the scheduler shows above the form so the planner can see which account a meeting lands in. */
    public record Account(boolean connected, String displayName, String email, String plan,
                          String connectedSince, String timezone) {}

    /**
     * Who the invitation says is hosting. The signed-in name is used when the deployment has one to
     * give — this app links no staff catalog, so the resolver hands back the login, and a client
     * reading "demo@weddingplanner.local is inviting you" would be the studio's name in the eyes of
     * everyone on that thread. The studio's own name stands in until a real one exists.
     */
    public String host(String displayName) {
        return displayName == null || displayName.isBlank() || displayName.contains("@") ? studio : displayName;
    }

    public Account account(String displayName) {
        return new Account(true, displayName, email, plan, since, java.time.ZoneId.systemDefault().getId());
    }

    public ZoomMeeting schedule(UUID requestId, UUID eventId, UUID conversationId, String topic,
            LocalDateTime startAt, int minutes, String timezone, boolean waitingRoom, boolean recording,
            String hostName) {
        byte[] seed = digest(requestId.toString());
        // 11 digits beginning 8 or 9, which is the range Zoom hands out for scheduled meetings.
        long number = 80_000_000_000L + Math.floorMod(read(seed, 0), 19_999_999_999L);
        String passcode = pick(seed, 8, 6, PASSCODE);
        String pwd = pick(seed, 14, 22, TOKEN);
        return new ZoomMeeting(eventId, conversationId, topic, number, passcode,
                "https://" + host + "/j/" + number + "?pwd=" + pwd, startAt, minutes, timezone,
                waitingRoom, recording, hostName, email);
    }

    private static long read(byte[] seed, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (seed[offset + i] & 0xFF);
        return value;
    }

    private static String pick(byte[] seed, int offset, int length, String alphabet) {
        var out = new StringBuilder(length);
        for (int i = 0; i < length; i++)
            out.append(alphabet.charAt((seed[(offset + i) % seed.length] & 0xFF) % alphabet.length()));
        return out.toString();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
