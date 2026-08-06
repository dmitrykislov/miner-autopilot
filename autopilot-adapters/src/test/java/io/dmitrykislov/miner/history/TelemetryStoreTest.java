package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.autopilot.AutopilotStreamService;
import io.dmitrykislov.miner.autopilot.ConsumptionSourceHub;
import io.dmitrykislov.miner.autopilot.SolarSourceHub;
import io.dmitrykislov.miner.port.MinerStatusSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetrySample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class TelemetryStoreTest {

    @TempDir
    Path tmp;

    /** Same day-file naming the store uses, so tests can address a specific file. */
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC);

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

    /**
     * Make appends to {@code prefix}'s day-file fail, by replacing that file with a read-only one.
     * Locking the *directory* is not enough — appending to an existing file ignores directory
     * permissions — and this must not depend on the test running as an unprivileged user, since a
     * root container can write to a read-only file anyway. So assert the write really fails first.
     */
    private void makeDayFileUnwritable(Path file) throws IOException {
        assertThat(file).exists();
        assertThat(file.toFile().setWritable(false)).isTrue();
        boolean actuallyReadOnly;
        try {
            Files.writeString(file, "probe\n", StandardOpenOption.APPEND);
            actuallyReadOnly = false;   // running privileged (root): the permission bit is advisory
        } catch (IOException expected) {
            actuallyReadOnly = true;
        }
        assumeTrue(actuallyReadOnly, "needs an unprivileged user for file permissions to bite");
    }

    @Test void aFailingAppendDoesNotPropagateOutOfRecord() throws IOException {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now, 3000.0, 2400, "MINING"));          // creates today's file
        Path today = tmp.resolve("samples-" + DAY.format(now) + ".log");
        makeDayFileUnwritable(today);
        try {
            // A failing append must not escape: it would skip the caller's prune() (see
            // TelemetryRecorder), so retention would stop running and the in-memory deque would grow
            // without bound — on a 128 MB heap that ends in an OutOfMemoryError.
            assertThatNoException().isThrownBy(() ->
                    s.recordSample(sample(now.plusSeconds(1), 3100.0, 2400, "MINING")));
            // …and the sample is still served from memory, so the chart and warm-up keep working.
            assertThat(s.samplesSince(now.minusSeconds(10)))
                    .as("a disk failure must cost durability, not the in-memory series")
                    .hasSize(2);
        } finally {
            today.toFile().setWritable(true);
        }
    }

    @Test void retentionStillPrunesThroughTheRecorderWhenWritesAreFailing() throws IOException {
        // Drive this through TelemetryRecorder, not by calling prune() directly: the bug was that a
        // thrown append skipped the recorder's own prune() call, so testing prune() in isolation
        // cannot see it. Recording via the recorder is what reproduces the real sequence.
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(40));                  // outside retention
        s.recordSample(sample(old, 3000.0, 2400, "MINING"));
        s.recordSample(sample(now, 3200.0, 2800, "MINING"));           // creates today's file
        assertThat(s.samplesSince(old.minus(Duration.ofDays(1)))).hasSize(2);

        Path today = tmp.resolve("samples-" + DAY.format(now) + ".log");
        makeDayFileUnwritable(today);
        try {
            var solar = new SolarSourceHub();
            solar.publish(new PowerReading(now, 3300.0));
            var recorder = new TelemetryRecorder(cfg(31), s, solar, new ConsumptionSourceHub(),
                    mock(MinerStatusSource.class), mock(AutopilotStreamService.class));
            assertThatNoException().isThrownBy(recorder::record);       // the append inside will fail

            // The 40-day-old sample must be gone: retention cannot depend on the disk working.
            var remaining = s.samplesSince(old.minus(Duration.ofDays(1)));
            assertThat(remaining)
                    .as("retention must keep pruning while writes are failing")
                    .isNotEmpty()
                    .noneSatisfy(x -> assertThat(x.at()).isEqualTo(old));
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

    // ---- the once-a-day sweep gate -----------------------------------------------------------
    // prune() runs every minute but a day-file can only fall out of retention when the UTC date
    // changes, so the directory scan is gated. Only the first branch was covered; these two pin the
    // gate itself, which is what keeps ~89k pointless syscalls a day off the SD card.

    @Test void theDirectorySweepIsSkippedOnASecondPruneTheSameDay() throws IOException {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now.minus(Duration.ofDays(40)), 1000.0, 1200, "MINING"));
        s.recordSample(sample(now.minusSeconds(30), 3000.0, 2400, "MINING"));

        s.prune(now);                                          // sweeps, deletes the stale day-file
        assertThat(Files.list(tmp).filter(p -> p.toString().endsWith(".log")).count()).isEqualTo(1);

        // Drop a stale file back in and prune again on the SAME UTC day: the gate must skip the scan,
        // so the file survives. (It is collected on the next date change.)
        Path resurrected = tmp.resolve("samples-2020-01-01.log");
        Files.writeString(resurrected, "");
        s.prune(now.plusSeconds(60));

        assertThat(resurrected)
                .as("the second prune of the day must not re-scan the directory")
                .exists();
    }

    @Test void theSweepResumesOnceTheUtcDateChanges() throws IOException {
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now.minusSeconds(30), 3000.0, 2400, "MINING"));
        s.prune(now);                                          // consumes today's sweep

        Path stale = tmp.resolve("samples-2020-01-01.log");
        Files.writeString(stale, "");
        s.prune(now.plus(Duration.ofDays(1)));                 // next UTC day → sweep runs again

        assertThat(stale)
                .as("a new UTC date must re-enable the sweep, or day-files accumulate forever")
                .doesNotExist();
    }

    // ---- persistence disabled at startup -----------------------------------------------------

    @Test void anUnusableHistoryDirLeavesTheStoreWorkingInMemoryOnly() throws IOException {
        // init() used to log "history disabled this run" while disabling nothing, so every later write
        // threw and pruning silently stopped. Point the store at a path that cannot be a directory.
        Path notADir = tmp.resolve("occupied");
        Files.writeString(notADir, "i am a regular file");
        TelemetryStore s = new TelemetryStore(new HistoryProperties(true, notADir.toString(), 60_000, 31));
        s.init();                                              // must not throw

        Instant now = Instant.now();
        assertThatNoException().isThrownBy(() -> s.recordSample(sample(now, 3000.0, 2400, "MINING")));

        // The chart and the autopilot's warm-up read from memory, so both keep working.
        assertThat(s.samplesSince(now.minusSeconds(60))).hasSize(1);
    }

    @Test void retentionKeepsRunningWhenTheHistoryDirIsUnusable() throws IOException {
        // The part that used to break: a failing store stopped pruning, so the deque grew without
        // bound. Samples are recorded oldest-first, matching how the recorder actually appends.
        Path notADir = tmp.resolve("occupied2");
        Files.writeString(notADir, "i am a regular file");
        TelemetryStore s = new TelemetryStore(new HistoryProperties(true, notADir.toString(), 60_000, 31));
        s.init();

        Instant now = Instant.now();
        s.recordSample(sample(now.minus(Duration.ofDays(40)), 1000.0, 1200, "MINING")); // out of retention
        s.recordSample(sample(now, 3000.0, 2400, "MINING"));
        s.prune(now);

        assertThat(s.samplesSince(now.minus(Duration.ofDays(60))))
                .as("pruning must not depend on the disk being usable")
                .hasSize(1);
    }

    @Test void pruneAssumesSamplesArriveInChronologicalOrder() {
        // Documents a real constraint rather than a defect: prune() stops at the first sample still
        // inside the window, so an out-of-order arrival behind a newer one is not collected. That
        // holds in production — the recorder appends Instant.now() each minute and load() sorts on
        // startup — but a future caller feeding history backwards would silently retain data.
        TelemetryStore s = store(31);
        Instant now = Instant.now();
        s.recordSample(sample(now, 3000.0, 2400, "MINING"));                            // newest FIRST
        s.recordSample(sample(now.minus(Duration.ofDays(40)), 1000.0, 1200, "MINING")); // then an old one
        s.prune(now);

        assertThat(s.samplesSince(now.minus(Duration.ofDays(60))))
                .as("the stale sample hides behind a newer one — prune scans from the front only")
                .hasSize(2);
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
