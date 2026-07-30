package io.dmitrykislov.miner.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A timestamp inside a log message must read the same way as the line prefix beside it. Instant's own
 * toString() renders UTC, which made an eleven-minute-old event look ten hours stale in a local-time
 * log — these tests pin the local rendering that avoids that.
 */
class LogTimeTest {

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test void rendersLocalTimeWithAnExplicitOffset() {
        // The exact case that misled: 05:16 UTC is 15:16 in Sydney, and the offset now says so.
        assertThat(LogTime.of(Instant.parse("2026-07-30T05:16:08.193Z"), SYDNEY))
                .isEqualTo("2026-07-30T15:16:08.193+10:00");
    }

    @Test void rendersUtcExplicitlyToo() {
        assertThat(LogTime.of(Instant.parse("2026-07-30T05:16:08.193Z"), UTC))
                .isEqualTo("2026-07-30T05:16:08.193Z");
    }

    @Test void keepsMillisecondPrecisionIncludingTrailingZeros() {
        assertThat(LogTime.of(Instant.parse("2026-07-30T05:00:00Z"), SYDNEY))
                .isEqualTo("2026-07-30T15:00:00.000+10:00");
    }

    @Test void reflectsADaylightSavingOffsetChange() {
        // Sydney is +11 in January. The offset is printed, so the reader never has to guess.
        assertThat(LogTime.of(Instant.parse("2026-01-15T05:00:00Z"), SYDNEY))
                .isEqualTo("2026-01-15T16:00:00.000+11:00");
    }

    @Test void theProductionEntryPointUsesTheMachineZoneNotUtc() {
        // Without this, LogTime.of(Instant) could be changed to render UTC and every other test here
        // would still pass — reintroducing the exact mismatch the class exists to prevent.
        Instant at = Instant.parse("2026-07-30T05:16:08.193Z");
        assertThat(LogTime.of(at)).isEqualTo(LogTime.of(at, ZoneId.systemDefault()));
    }

    @Test void nullIsReportedRatherThanCrashing() {
        assertThat(LogTime.of(null)).isEqualTo("unknown");
    }
}
