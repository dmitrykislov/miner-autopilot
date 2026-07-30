package io.dmitrykislov.miner.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats an {@link Instant} the same way the log's own line prefix is formatted, so timestamps
 * inside a message can be compared with the timestamps beside them.
 *
 * <p>Why this exists: {@code Instant.toString()} always renders UTC ({@code …T05:16:08.193Z}) while
 * the logging pattern renders the JVM's local zone ({@code …T15:16:08.193+10:00}). Mixing the two in
 * one line is genuinely misleading — a change made eleven minutes ago read as ten hours old, and cost
 * real time during an investigation. Everything human-readable in the logs now uses local time with
 * an explicit offset, so it is both consistent and unambiguous.
 *
 * <p>Machine-readable timestamps are deliberately left alone: the JSON API and the history files keep
 * epoch millis / UTC ISO-8601, which is correct for storage and for the browser to localise.
 */
public final class LogTime {

    /** Matches Spring Boot's default log pattern (ISO-8601 local time, millis, explicit offset). */
    private static final DateTimeFormatter LOCAL_WITH_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private LogTime() {
    }

    /** {@code at} in the JVM's local zone with its offset, or {@code "unknown"} when null. */
    public static String of(Instant at) {
        return of(at, ZoneId.systemDefault());
    }

    /** Testable variant: {@code at} rendered in {@code zone}. */
    public static String of(Instant at, ZoneId zone) {
        return at == null ? "unknown" : LOCAL_WITH_OFFSET.format(at.atZone(zone));
    }
}
