package io.dmitrykislov.miner.history;

import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetryHistory;
import io.dmitrykislov.miner.port.TelemetrySample;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * A deliberately tiny time-series store for the history chart — no database, just append-only text
 * files (one per UTC day) plus an in-memory mirror for fast reads. Designed for a Raspberry Pi:
 * a month of minute samples is a few MB on disk and in heap. Data older than the retention window is
 * discarded from memory and its day-files deleted.
 *
 * <p>Line formats (compact, self-contained, no serialization library):
 * <pre>
 *   samples-YYYY-MM-DD.log : atMs,solarW,consumptionW,minerPowerW,minerDrawW,minerState   (empty = null)
 *   events-YYYY-MM-DD.log  : atMs\taction\tfromW\ttoW\treason                               (tab-separated)
 * </pre>
 *
 * <p>Only the single-threaded recorder writes; the web layer only reads the in-memory deques.
 * All deque access is synchronized.
 */
@Component
public class TelemetryStore implements TelemetryHistory {

    private static final Logger log = LoggerFactory.getLogger(TelemetryStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final HistoryProperties cfg;
    private final Path dir;
    private final Deque<TelemetrySample> samples = new ArrayDeque<>();
    private final Deque<PowerChangeEvent> events = new ArrayDeque<>();

    public TelemetryStore(HistoryProperties cfg) {
        this.cfg = cfg;
        this.dir = Path.of(cfg.dir());
    }

    /** Set when the store could not be initialised: keep serving in memory, stop touching the disk. */
    private volatile boolean persistenceDisabled;
    /** Latches the "writes are failing" warning so a broken disk can't flood the log every minute. */
    private boolean writeFailureLogged;
    /** UTC date of the last day-file sweep, so retention scans the directory once a day, not per call. */
    private LocalDate lastFilePruneDay;

    @PostConstruct
    void init() {
        if (!cfg.enabled()) return;
        try {
            Files.createDirectories(dir);
            load(Instant.now());
            log.info("History: loaded {} samples, {} events from {} (retain {}d)",
                    samples.size(), events.size(), dir.toAbsolutePath(), cfg.retentionDays());
        } catch (Exception e) {
            // Persistence only — the in-memory history (and so the chart) keeps working, and
            // retention keeps pruning. Previously this said "history disabled" without disabling
            // anything, so every later write threw and pruning silently stopped running.
            persistenceDisabled = true;
            log.warn("History: could not initialise store at {} — continuing in memory only, "
                    + "nothing will be persisted this run: {}", dir.toAbsolutePath(), e.toString());
        }
    }

    // ---- writes (recorder thread only) --------------------------------------

    public synchronized void recordSample(TelemetrySample s) {
        if (!cfg.enabled()) return;
        samples.addLast(s);
        append("samples-", s.at(), serialize(s));
    }

    public synchronized void recordEvent(PowerChangeEvent e) {
        if (!cfg.enabled()) return;
        events.addLast(e);
        append("events-", e.at(), serialize(e));
    }

    /** Drop in-memory data older than the retention window and delete day-files that fell out of it. */
    public synchronized void prune(Instant now) {
        if (!cfg.enabled()) return;
        Instant cutoff = now.minus(cfg.retention());
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) samples.removeFirst();
        while (!events.isEmpty() && events.peekFirst().at().isBefore(cutoff)) events.removeFirst();
        // A day-file can only fall out of retention when the UTC date changes, so scan the directory
        // once a day instead of on every call. The recorder prunes every minute, and each scan listed
        // ~60 files and parsed each name — about 89k pointless syscalls a day, inside the lock the
        // history endpoints also need.
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!today.equals(lastFilePruneDay)) {
            // Record the day only when the directory was actually listed. Marking it up front would
            // burn the day's single attempt on a transient failure (a read-only remount, a listing
            // error) and leave day-files accumulating for 24 h with nothing above DEBUG to show why.
            if (deleteFilesBefore(cutoff)) lastFilePruneDay = today;
        }
    }

    // ---- reads (web thread) -------------------------------------------------

    public synchronized List<TelemetrySample> samplesSince(Instant from) {
        return samplesBetween(from, Instant.MAX);
    }

    public synchronized List<PowerChangeEvent> eventsSince(Instant from) {
        return eventsBetween(from, Instant.MAX);
    }

    /** The most recently recorded power-change event (newest), or null if none — used to restore the
     *  autopilot's "last change" across a restart so its cooldown/dampening survive. */
    public synchronized PowerChangeEvent latestEvent() {
        return events.peekLast();
    }

    /** Samples with {@code from ≤ at ≤ to}, time-ascending. */
    public synchronized List<TelemetrySample> samplesBetween(Instant from, Instant to) {
        List<TelemetrySample> out = new ArrayList<>();
        for (TelemetrySample s : samples) {
            if (!s.at().isBefore(from) && !s.at().isAfter(to)) out.add(s);
        }
        return out;
    }

    /** Events with {@code from ≤ at ≤ to}, time-ascending. */
    public synchronized List<PowerChangeEvent> eventsBetween(Instant from, Instant to) {
        List<PowerChangeEvent> out = new ArrayList<>();
        for (PowerChangeEvent e : events) {
            if (!e.at().isBefore(from) && !e.at().isAfter(to)) out.add(e);
        }
        return out;
    }

    // ---- persistence --------------------------------------------------------

    /**
     * Append one line to the day-file, <b>swallowing I/O errors</b>.
     *
     * <p>This must never throw. It is called from {@code recordSample}/{@code recordEvent}, and the
     * recorder calls {@code prune()} immediately afterwards — so an escaping exception would skip
     * retention entirely. Every minute the deque would gain a sample and never lose one, growing past
     * the retention limit until the 128 MB heap ran out. The trigger for a failing write is usually a
     * full SD card, which is exactly when pruning matters most.
     *
     * <p>The in-memory history stays authoritative for the running process, so the dashboard and the
     * autopilot's warm-up are unaffected; only durability across a restart is lost.
     */
    private void append(String prefix, Instant at, String line) {
        if (persistenceDisabled) return;
        Path file = dir.resolve(prefix + DAY.format(at) + ".log");
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writeFailureLogged = false; // recovered — re-arm the warning
        } catch (IOException e) {
            if (!writeFailureLogged) {
                writeFailureLogged = true;
                log.warn("History: cannot write {} — keeping history in memory only until writes "
                        + "recover (disk full or read-only?): {}", file, e.toString());
            }
        }
    }

    private void load(Instant now) throws IOException {
        Instant cutoff = now.minus(cfg.retention());
        if (!Files.isDirectory(dir)) return;
        List<TelemetrySample> loadedSamples = new ArrayList<>();
        List<PowerChangeEvent> loadedEvents = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.sorted().forEach(f -> {
                String name = f.getFileName().toString();
                if (fileDateBefore(name, cutoff)) return; // skip out-of-retention files (pruned below)
                if (name.startsWith("samples-") && name.endsWith(".log")) {
                    forEachLine(f, l -> { TelemetrySample s = parseSample(l); if (s != null && !s.at().isBefore(cutoff)) loadedSamples.add(s); });
                } else if (name.startsWith("events-") && name.endsWith(".log")) {
                    forEachLine(f, l -> { PowerChangeEvent e = parseEvent(l); if (e != null && !e.at().isBefore(cutoff)) loadedEvents.add(e); });
                }
            });
        }
        loadedSamples.sort((a, b) -> a.at().compareTo(b.at()));
        loadedEvents.sort((a, b) -> a.at().compareTo(b.at()));
        samples.addAll(loadedSamples);
        events.addAll(loadedEvents);
        deleteFilesBefore(cutoff);
    }

    /** @return true if the directory was listed, so the caller can tell a real sweep from a no-op. */
    private boolean deleteFilesBefore(Instant cutoff) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                if (fileDateBefore(f.getFileName().toString(), cutoff)) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        log.debug("history: could not delete stale file {}: {}", f, e.toString());
                    }
                }
            });
            return true;
        } catch (IOException e) {
            log.debug("history: prune listing failed: {}", e.toString());
            return false;
        }
    }

    /** A day-file is out of retention when its whole day ends before the cutoff. */
    private boolean fileDateBefore(String name, Instant cutoff) {
        // Extract the trailing yyyy-MM-dd before ".log" (e.g. "samples-2026-07-27.log").
        if (!name.endsWith(".log") || name.length() < 14) return false;
        String date = name.substring(name.length() - 14, name.length() - 4); // yyyy-MM-dd
        try {
            LocalDate d = LocalDate.parse(date);
            // Keep the file if any instant of that UTC day is ≥ cutoff (i.e. end-of-day ≥ cutoff).
            Instant endOfDay = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return endOfDay.isBefore(cutoff) || endOfDay.equals(cutoff);
        } catch (Exception ex) {
            return false; // unrecognised file — never delete
        }
    }

    private interface LineConsumer { void accept(String line); }

    private void forEachLine(Path file, LineConsumer c) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(l -> { if (!l.isBlank()) c.accept(l); });
        } catch (IOException e) {
            log.debug("history: could not read {}: {}", file, e.toString());
        }
    }

    // ---- line (de)serialization --------------------------------------------

    static String serialize(TelemetrySample s) {
        return s.at().toEpochMilli() + "," + num(s.solarW()) + "," + num(s.consumptionW())
                + "," + num(s.minerPowerW()) + "," + num(s.minerDrawW()) + "," + str(s.minerState());
    }

    static TelemetrySample parseSample(String line) {
        String[] f = line.split(",", -1);
        if (f.length < 6) return null;
        try {
            return new TelemetrySample(
                    Instant.ofEpochMilli(Long.parseLong(f[0])),
                    parseD(f[1]), parseD(f[2]), parseI(f[3]), parseI(f[4]),
                    f[5].isEmpty() ? null : f[5].intern());
        } catch (RuntimeException ex) {
            return null; // skip a corrupt line rather than fail the whole load
        }
    }

    static String serialize(PowerChangeEvent e) {
        return e.at().toEpochMilli() + "\t" + str(e.action()) + "\t" + num(e.fromW())
                + "\t" + num(e.toW()) + "\t" + clean(e.reason());
    }

    static PowerChangeEvent parseEvent(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 5) return null;
        try {
            return new PowerChangeEvent(Instant.ofEpochMilli(Long.parseLong(f[0])),
                    f[1].isEmpty() ? null : f[1], parseI(f[2]), parseI(f[3]),
                    f[4].isEmpty() ? null : f[4]);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String num(Double d) { return d == null ? "" : Double.toString(d); }
    private static String num(Integer i) { return i == null ? "" : Integer.toString(i); }
    private static String str(String s) { return s == null ? "" : s; }
    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\t\r\n]", " "); }
    private static Double parseD(String s) { return s.isEmpty() ? null : Double.valueOf(s); }
    private static Integer parseI(String s) { return s.isEmpty() ? null : Integer.valueOf(s); }
}
