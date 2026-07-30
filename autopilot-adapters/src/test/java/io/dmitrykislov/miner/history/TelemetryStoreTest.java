package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetrySample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryStoreTest {

    @TempDir
    Path tmp;

    private HistoryProperties cfg(int retentionDays) {
        return new HistoryProperties(true, tmp.toString(), 60_000, retentionDays);
    }

    private TelemetryStore store(int retentionDays) {
        TelemetryStore s = new TelemetryStore(cfg(retentionDays));
        s.init();
        return s;
    }

    private TelemetrySample sample(Instant at, Double solar, Integer power, String state) {
        return new TelemetrySample(at, solar, solar == null ? null : solar - 1500, power, power, state);
    }

    // ---- disk failure must never break retention (the Pi's likeliest failure: a full SD card) ----

    @Test void anUnwritableDirectoryDoesNotPropagateOutOfRecord() {
        TelemetryStore s = store(31);
        assertThat(tmp.toFile().setWritable(false)).isTrue();
        try {
            // A failing append must not escape: it would skip the caller's prune() (see
            // TelemetryRecorder), so retention would stop running and the in-memory deque would grow
            // without bound — on a 128 MB heap that ends in an OutOfMemoryError.
            Instant now = Instant.now();
            s.recordSample(sample(now, 3000.0, 2400, "MINING"));
            s.recordEvent(new PowerChangeEvent(now, "STEP_UP", 2400, 2800, "surplus rose"));
        } finally {
            tmp.toFile().setWritable(true);
        }
    }

    @Test void retentionStillPrunesWhenWritesAreFailing() throws IOException {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now.minus(Duration.ofDays(40)), 3000.0, 2400, "MINING")); // outside retention
        s.recordSample(sample(now, 3200.0, 2800, "MINING"));

        // Make today's day-file itself read-only — appending to an existing file ignores the
        // directory's permissions, so the file is what has to be locked to simulate a failing write.
        Path today = Files.list(tmp).filter(p -> p.getFileName().toString().startsWith("samples-"))
                .findFirst().orElseThrow();
        assertThat(today.toFile().setWritable(false)).isTrue();
        try {
            s.recordSample(sample(now.plusSeconds(1), 3300.0, 2800, "MINING")); // append fails
            s.prune(now);
            // The 40-day-old sample must still be gone: pruning cannot depend on the disk working.
            assertThat(s.samplesSince(now.minus(Duration.ofDays(60))))
                    .as("retention must keep working while the disk is failing")
                    .allSatisfy(x -> assertThat(x.at()).isAfter(now.minus(Duration.ofDays(32))));
        } finally {
            today.toFile().setWritable(true);
        }
    }

    @Test void recordsAndQueriesSamplesAndEvents() {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now.minusSeconds(120), 3000.0, 2400, "MINING"));
        s.recordSample(sample(now.minusSeconds(60), 3200.0, 2800, "MINING"));
        s.recordEvent(new PowerChangeEvent(now.minusSeconds(90), "STEP_UP", 2400, 2800, "surplus rose"));

        assertThat(s.samplesSince(now.minusSeconds(180))).hasSize(2);
        assertThat(s.samplesSince(now.minusSeconds(90))).hasSize(1); // only the newer one
        assertThat(s.eventsSince(now.minusSeconds(180))).singleElement()
                .satisfies(e -> {
                    assertThat(e.action()).isEqualTo("STEP_UP");
                    assertThat(e.fromW()).isEqualTo(2400);
                    assertThat(e.toW()).isEqualTo(2800);
                });
    }

    @Test void samplesAndEventsBetweenAreInclusiveOfBothBounds() {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        Instant a = now.minusSeconds(300), b = now.minusSeconds(200), c = now.minusSeconds(100);
        s.recordSample(sample(a, 1000.0, 1200, "MINING"));
        s.recordSample(sample(b, 2000.0, 1600, "MINING"));
        s.recordSample(sample(c, 3000.0, 2000, "MINING"));
        s.recordEvent(new PowerChangeEvent(b, "STEP_UP", 1200, 1600, "up"));

        // [a, b] includes both endpoints, excludes c.
        assertThat(s.samplesBetween(a, b)).extracting(TelemetrySample::solarW)
                .containsExactly(1000.0, 2000.0);
        assertThat(s.eventsBetween(a, c)).singleElement()
                .satisfies(e -> assertThat(e.at()).isEqualTo(b));
        assertThat(s.eventsBetween(c, now)).isEmpty(); // event at b is before the window
    }

    @Test void pruneDropsInMemoryAndDeletesStaleDayFiles() throws IOException {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now.minus(Duration.ofDays(40)), 1000.0, 1200, "MINING")); // out of retention
        s.recordSample(sample(now.minusSeconds(30), 3000.0, 2400, "MINING"));            // in retention

        long logsBefore = Files.list(tmp).filter(p -> p.toString().endsWith(".log")).count();
        assertThat(logsBefore).isEqualTo(2); // two different day-files

        s.prune(now);

        assertThat(s.samplesSince(now.minus(Duration.ofDays(60)))).hasSize(1); // old one dropped from memory
        long logsAfter = Files.list(tmp).filter(p -> p.toString().endsWith(".log")).count();
        assertThat(logsAfter).isEqualTo(1); // the 40-day-old day-file was deleted
    }

    @Test void persistsAcrossRestart() {
        TelemetryStore s1 = store(31);
        Instant now = Instant.now();
        s1.recordSample(sample(now.minusSeconds(120), 3000.0, 2400, "MINING"));
        s1.recordSample(sample(now.minusSeconds(60), null, null, "OFFLINE")); // null fields survive
        s1.recordEvent(new PowerChangeEvent(now.minusSeconds(90), "STOP", 2400, null, "cloud, big drop"));

        // A fresh store over the same dir loads what was written.
        TelemetryStore s2 = store(31);
        List<TelemetrySample> loaded = s2.samplesSince(now.minus(Duration.ofDays(1)));
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(1).solarW()).isNull();
        assertThat(loaded.get(1).minerState()).isEqualTo("OFFLINE");
        assertThat(s2.eventsSince(now.minus(Duration.ofDays(1)))).singleElement()
                .satisfies(e -> {
                    assertThat(e.action()).isEqualTo("STOP");
                    assertThat(e.toW()).isNull();
                    assertThat(e.reason()).isEqualTo("cloud, big drop"); // comma preserved (events are tab-delimited)
                });
    }

    @Test void disabledStoreRecordsNothing() {
        TelemetryStore s = new TelemetryStore(new HistoryProperties(false, tmp.toString(), 60_000, 31));
        s.init();
        s.recordSample(sample(Instant.now(), 3000.0, 2400, "MINING"));
        assertThat(s.samplesSince(Instant.EPOCH)).isEmpty();
    }

    @Test void sampleLineRoundTripsNullsAndValues() {
        TelemetrySample full = new TelemetrySample(Instant.ofEpochMilli(1_700_000_000_000L),
                3500.5, 1800.25, 2400, 2350, "MINING");
        assertThat(TelemetryStore.parseSample(TelemetryStore.serialize(full))).isEqualTo(full);

        TelemetrySample sparse = new TelemetrySample(Instant.ofEpochMilli(1_700_000_060_000L),
                null, null, null, null, null);
        assertThat(TelemetryStore.parseSample(TelemetryStore.serialize(sparse))).isEqualTo(sparse);
    }

    @Test void eventLineSanitizesTabsButKeepsCommasInReason() {
        PowerChangeEvent e = new PowerChangeEvent(Instant.ofEpochMilli(1_700_000_000_000L),
                "STEP_DOWN", 3600, 1600, "surplus 1800W (importing hard),\tdown\nto 1600W");
        PowerChangeEvent back = TelemetryStore.parseEvent(TelemetryStore.serialize(e));
        assertThat(back.at()).isEqualTo(e.at());
        assertThat(back.action()).isEqualTo("STEP_DOWN");
        assertThat(back.fromW()).isEqualTo(3600);
        assertThat(back.toW()).isEqualTo(1600);
        // tabs/newlines flattened to spaces (they'd corrupt the tab-delimited line); commas kept.
        assertThat(back.reason()).isEqualTo("surplus 1800W (importing hard), down to 1600W");
    }

    @Test void corruptLinesAreSkippedNotFatal() {
        assertThat(TelemetryStore.parseSample("not-a-line")).isNull();
        assertThat(TelemetryStore.parseSample("123,abc,,,,MINING")).isNull(); // bad double
        assertThat(TelemetryStore.parseEvent("only\ttwo")).isNull();
    }
}
