package com.weddingplanner.crm.zoom;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * One scheduled meeting in the planner's Zoom workspace, in the shape Zoom's
 * {@code POST /users/me/meetings} answers with: the numeric meeting id, the passcode, and the join
 * URL that already carries the encrypted passcode so a guest never types it.
 *
 * <p>The record is also the storage format. A meeting lives in the body of the internal event it
 * writes into the conversation rather than in a catalog of its own, because the chat timeline is
 * the only place it is ever read from — {@link #body()} writes it and {@link #parse} reads it back.</p>
 */
public record ZoomMeeting(
        UUID eventId, UUID conversationId, String topic, long meetingNumber, String passcode,
        String joinUrl, LocalDateTime startAt, int minutes, String timezone,
        boolean waitingRoom, boolean recording, String hostName, String hostEmail) {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SENT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm", Locale.ENGLISH);

    /** {@code 812 3456 7890} — how Zoom prints a meeting id everywhere a person has to read it. */
    public String meetingId() {
        String digits = Long.toString(meetingNumber);
        return digits.length() == 11
                ? digits.substring(0, 3) + " " + digits.substring(3, 7) + " " + digits.substring(7)
                : digits;
    }

    public String options() {
        return (waitingRoom ? "Waiting room on" : "Waiting room off")
                + " · " + (recording ? "Cloud recording on" : "Cloud recording off");
    }

    /**
     * The internal event the inbox timeline renders. The first line keeps the CRM's own
     * {@code Meeting planned · Scheduled for …} heading so a host without the Zoom card still draws
     * the meeting as a scheduled activity instead of as an unlabelled system line.
     */
    public String body() {
        return "Meeting planned · Scheduled for " + WHEN.format(startAt) + "\n"
                + "Zoom meeting: " + topic + "\n"
                + "Join: " + joinUrl + "\n"
                + "Meeting ID: " + meetingId() + "\n"
                + "Passcode: " + passcode + "\n"
                + "Duration: " + minutes + " minutes\n"
                + "Timezone: " + timezone + "\n"
                + "Host: " + hostName + " · " + hostEmail + "\n"
                + "Starts: " + STORED.format(startAt) + "\n"
                + "Options: " + options();
    }

    /**
     * What the client is sent: when, and the link. Zoom's own eight-line invitation block is the
     * wrong thing to paste into a WhatsApp thread, and the meeting id and passcode it repeats are
     * already carried by the URL — they belong on the planner's card, not in the couple's chat.
     */
    public String invitation() {
        return SENT.format(startAt) + " (" + timezone + ")\n" + joinUrl;
    }

    /** Reads back a meeting written by {@link #body()}; empty when the event is not a Zoom meeting. */
    public static java.util.Optional<ZoomMeeting> parse(UUID eventId, UUID conversationId, String body) {
        if (body == null || !body.contains("\nZoom meeting: ")) return java.util.Optional.empty();
        String topic = field(body, "Zoom meeting");
        String join = field(body, "Join");
        String id = field(body, "Meeting ID").replace(" ", "");
        String starts = field(body, "Starts");
        if (topic.isBlank() || join.isBlank() || id.isBlank() || starts.isBlank()) return java.util.Optional.empty();
        String host = field(body, "Host");
        int split = host.lastIndexOf(" · ");
        String options = field(body, "Options");
        try {
            return java.util.Optional.of(new ZoomMeeting(eventId, conversationId, topic, Long.parseLong(id),
                    field(body, "Passcode"), join, LocalDateTime.parse(starts, STORED),
                    minutes(field(body, "Duration")), field(body, "Timezone"),
                    options.contains("Waiting room on"), options.contains("Cloud recording on"),
                    split < 0 ? host : host.substring(0, split), split < 0 ? "" : host.substring(split + 3)));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static int minutes(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 30 : Integer.parseInt(digits);
    }

    private static String field(String body, String key) {
        for (String line : body.split("\n"))
            if (line.startsWith(key + ": ")) return line.substring(key.length() + 2).strip();
        return "";
    }
}
