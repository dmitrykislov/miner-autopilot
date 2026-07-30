package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.port.MinerStatus;
import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetryHistory;
import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.MinerDriver;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import io.dmitrykislov.miner.util.LogTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Smoothed solar-surplus autopilot. Every {@code house.autopilot.interval-ms} (default 30 s) it
 * reads a <b>fresh</b> miner state and the time-averaged surplus signals, asks the pure
 * {@link AutopilotGovernor} what to do, and applies it via {@link MinerService}. Off by default
 * (it drives real mining hardware); can be toggled at runtime via the UI.
 *
 * <p>The averaging engine ({@link EnergyAverages}, fed by {@link EnergySampler}) rides through brief
 * clouds; the governor quantizes power onto a ladder, dampens ramp-up, and reacts fast on drops. See
 * {@link AutopilotGovernor} for the decision model.
 *
 * <p>State handling is deliberately conservative:
 * <ul>
 *   <li>every tick starts with a <b>live</b> {@link MinerService#refresh()} — never a cached
 *       status — so decisions use the miner's actual on/off state and power target;</li>
 *   <li>the surplus is trusted only when the live feed is valid <em>now</em> (the solar and
 *       consumption source ports both have a fresh reading) <b>and</b> the rolling windows are fresh — otherwise
 *       the governor is told {@code dataFresh = false} and stops a running miner for safety;</li>
 *   <li>each mutating operation <b>re-verifies</b> the miner state immediately before it runs
 *       (start only if still off, step only if still mining, stop only if still running), so a
 *       state change between decision and action can't cause a wrong op.</li>
 * </ul>
 */
@Service
public class MinerAutopilot {

    private static final Logger log = LoggerFactory.getLogger(MinerAutopilot.class);

    private final EnergyAverages energy;
    private final SolarSource solarSource;
    private final ConsumptionSource consumptionSource;
    private final MinerDriver minerService;
    private final HouseProperties.Miner minerCfg;
    private final AutopilotGovernor governor;
    private final AutopilotStreamService statusStream;
    private final Duration maxSnapshotAge;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicReference<AutopilotStatus.Change> lastChange = new AtomicReference<>();
    private volatile Instant evaluatedAt;
    private volatile String lastDecision;
    /** Previous decision with numbers masked, so a repeated hold whose watt figures drift stays quiet. */
    private String lastDecisionKey;

    /** A start command we issued but have not yet seen take effect. */
    private record PendingStart(int targetW, String reason, int ticksWaited) {}
    private volatile PendingStart pendingStart;
    /** Ticks to keep waiting for a start to show up before giving up on confirming it. */
    private static final int MAX_START_CONFIRM_TICKS = 10;
    // When the miner began continuously mining (null when not mining). Drives the governor's
    // "mined long enough for a valid up-average" guard; seeded from the miner's uptime on the FIRST
    // observation (so a long-running miner can ramp soon after a restart), but reset to
    // "now" on an observed resume from a non-mining state — see trackMiningSince.
    private volatile Instant miningSince;
    // False until the first tick has observed the miner. Lets us tell a first observation (trust the
    // miner's uptime) from an observed resume (mining truly just (re)started). A monotonic boolean —
    // not the last state — so a one-off null/garbled state can't be mistaken for "never observed".
    private volatile boolean minerObserved;

    public MinerAutopilot(EnergyAverages energy, SolarSource solarSource, ConsumptionSource consumptionSource,
                          MinerDriver minerService, HouseProperties props,
                          AutopilotStreamService statusStream, TelemetryHistory history) {
        this.energy = energy;
        this.solarSource = solarSource;
        this.consumptionSource = consumptionSource;
        this.minerService = minerService;
        this.minerCfg = props.miner();
        this.statusStream = statusStream;
        HouseProperties.Autopilot cfg = props.autopilot();
        this.governor = new AutopilotGovernor(new AutopilotGovernor.Config(
                cfg.floorW(), minerCfg.maxPowerW(), cfg.stepW(), cfg.headroomW(), cfg.startSurplusW(),
                Duration.ofMillis(cfg.upIntervalMs()), Duration.ofMillis(cfg.downIntervalMs()),
                Duration.ofMillis(cfg.longWindowMs()), cfg.upMaxRungsPerCycle(), cfg.emergencyGapW(),
                Duration.ofMillis(cfg.minRunMs())));
        // Tolerate a few missed/slow polls, but treat a longer gap as "no longer known": a stalled
        // poller keeps handing back its last reading, so without this the surplus could be piloted on
        // stale data. Judge against the SLOWER of the two feeds' cadences (×4 to ride out transient
        // GC/scheduling jitter): the consumption reading arrives from Solar Analytics on its own
        // interval, so tying this solely to the inverter poll would wrongly starve consumption if the
        // inverter happens to poll faster than Solar Analytics.
        long slowestPollMs = Math.max(props.inverter().pollIntervalMs(), props.solarAnalytics().pollIntervalMs());
        this.maxSnapshotAge = Duration.ofMillis(Math.max(1L, slowestPollMs) * 4);
        this.enabled.set(cfg.enabled());
        // Restore the last power change from persisted history so a restart doesn't forget
        // what it just did: the governor's restart cooldown / up-dampening are measured from
        // lastChangeAt, so without this a reboot would reset them to "elapsed" and could act too soon.
        restoreLastChange(history);
        this.lastDecision = cfg.enabled() ? "enabled — awaiting first evaluation" : "disabled";
        statusStream.publish(status());
    }

    private void restoreLastChange(TelemetryHistory history) {
        try {
            PowerChangeEvent e = history.latestEvent();
            if (e != null) {
                lastChange.set(new AutopilotStatus.Change(e.at(), e.action(), e.fromW(), e.toW(), e.reason()));
                // Local time with offset, matching the log's own prefix — an Instant would print UTC
                // here and read as hours stale next to a local timestamp (see LogTime).
                log.info("autopilot: restored last change from history — {} at {}",
                        e.action(), LogTime.of(e.at()));
            }
        } catch (Exception ex) {
            log.debug("autopilot: could not restore last change from history: {}", ex.toString());
        }
    }

    // ---- runtime control / status (used by the API + UI) --------------------

    public boolean isEnabled() {
        return enabled.get();
    }

    /** Turn the control loop on/off at runtime; publishes the new status. */
    public void setEnabled(boolean on) {
        boolean was = enabled.getAndSet(on);
        if (was != on) {
            log.info("autopilot {} via API", on ? "ENABLED" : "DISABLED");
            lastDecision = on ? "enabled — awaiting next evaluation" : "disabled";
        }
        statusStream.publish(status());
    }

    public AutopilotStatus status() {
        AutopilotStatus.Change c = lastChange.get();
        return new AutopilotStatus(enabled.get(), evaluatedAt, lastDecision,
                c != null ? c.at() : null, c);
    }

    @Scheduled(fixedDelayString = "${house.autopilot.interval-ms:30000}",
               initialDelayString = "${house.autopilot.interval-ms:30000}")
    public void tick() {
        if (!enabled.get()) return;
        try {
            evaluate();
        } finally {
            evaluatedAt = Instant.now();
            statusStream.publish(status());
        }
    }

    private void evaluate() {
        Instant now = Instant.now();
        // Always start from a fresh, live miner state.
        MinerStatus st = minerService.refresh();
        if (st == null) {
            lastDecision = "miner status unavailable — skipping";
            log.debug("autopilot: {}", lastDecision);
            return;
        }
        // NOTE: we deliberately do NOT bail when the miner is unreachable. A Braiins miner whose
        // BOSMiner service is stopped reports its GraphQL as "unavailable" (→ unreachable), yet it
        // can still be (re)started. The governor treats an unreachable miner as OFF and start-eligible,
        // so it can recover the miner it previously stopped once the surplus returns.

        trackMiningSince(st, now);

        // The surplus is trustworthy only if the live feed is valid right now AND the rolling
        // windows are fresh. feedValid catches a stale solar/consumption source immediately (a
        // stronger, instantaneous signal than window staleness); energy.dataFresh catches a
        // dead sampler. Either failing → the governor treats the surplus as unknown.
        boolean dataFresh = feedValid(now) && energy.dataFresh(now);
        EnergyAverages.Signals sig = energy.signals(now);

        AutopilotGovernor.Input input = new AutopilotGovernor.Input(
                now, st.reachable(), st.running(), MinerStatus.SUSPENDED.equals(st.state()),
                st.powerTargetW(), miningSince, lastChangeAt(), dataFresh,
                sig.shortSurplusW(), sig.longSurplusW());

        AutopilotDecision d = governor.decide(input);
        // Log an action, or a genuinely new explanation, at INFO; a repeated "still holding" goes to
        // DEBUG. At one tick every 30 s an unconditional INFO wrote ~2,880 lines/day — a couple of MB
        // of needless SD-card writes on the Pi, and enough noise to bury the lines that matter.
        //
        // Compare on the reason with its NUMBERS MASKED. The steady-state hold reason is
        // "surplus %dW, holding at %dW", whose watt figures move every tick, so comparing the
        // formatted strings marked almost every hold as new and suppressed nothing.
        String key = d.action() + "|" + d.reason().replaceAll("-?\\d+", "#");
        boolean interesting = d.action() != AutopilotDecision.Action.NONE || !key.equals(lastDecisionKey);
        lastDecisionKey = key;
        lastDecision = d.reason();
        // No "autopilot:" prefix here — the reason already carries one (see AutopilotGovernor.decision)
        // and the logger prints the class name, so adding it produced "autopilot: autopilot: …".
        if (interesting) {
            log.info("{}", d.reason());
        } else {
            log.debug("{}", d.reason());
        }
        // Resolve a start we couldn't confirm earlier, now that we know what this tick intends. Doing
        // it AFTER the decision matters: if we are about to stop, that pending start is moot and
        // confirming it would both record a pointless change and fire a target command at a miner we
        // are switching off in the same tick.
        if (d.action() == AutopilotDecision.Action.STOP) {
            discardPendingStart("stopping");
        } else {
            confirmPendingStart(st);
        }
        apply(d);
    }

    /** True when BOTH the solar and consumption source ports have a reading fresh enough to trust. */
    private boolean feedValid(Instant now) {
        return isFresh(solarSource.latest().orElse(null), now)
                && isFresh(consumptionSource.latest().orElse(null), now);
    }

    /**
     * Is this reading recent enough to act on? A reading stamped in the <b>future</b> is not: the
     * negative age would otherwise compare as fresh (see {@link RollingWindow}), letting the autopilot
     * act on a clock artifact. Treating it as stale routes to the safe path — stop a running miner.
     */
    private boolean isFresh(PowerReading r, Instant now) {
        if (r == null) return false;
        Duration age = Duration.between(r.at(), now);
        // |age| — a reading slightly AHEAD of `now` is the common case, not an error: `now` is captured
        // once at the top of the tick and we then spend blocking miner I/O before getting here, while
        // the pollers keep stamping readings on their own threads. Rejecting those outright made the
        // feed look dead and stopped a healthy miner every time the race was lost. Only an excursion
        // bigger than the staleness window itself (a real clock step) counts as untrustworthy.
        return age.abs().compareTo(maxSnapshotAge) <= 0;
    }

    private void trackMiningSince(MinerStatus st, Instant now) {
        if (MinerStatus.MINING.equals(st.state())) {
            if (miningSince == null) {
                if (minerObserved) {
                    // We observed a non-mining state before and now see mining → it truly just
                    // (re)started (e.g. resume from SUSPENDED). Start the clock now so the up-average
                    // guard makes the long window refill with real mining samples before any ramp —
                    // the window was contaminated while the miner drew ~0 W.
                    miningSince = now;
                } else {
                    // First observation after a restart: trust the miner's own uptime, so a
                    // long-running miner isn't made to wait a whole long-window before it can ramp.
                    Long up = st.uptimeSeconds();
                    miningSince = (up != null && up > 0) ? now.minusSeconds(up) : now;
                }
            }
        } else {
            miningSince = null; // suspended/stopped/offline breaks continuous mining
        }
        minerObserved = true;
    }

    private Instant lastChangeAt() {
        AutopilotStatus.Change c = lastChange.get();
        return c != null ? c.at() : null;
    }

    private void apply(AutopilotDecision d) {
        switch (d.action()) {
            case START -> startAtMin(d.targetPowerW(), d.reason());
            case STEP_UP, STEP_DOWN -> setPowerIfMining(d.action().name(), d.targetPowerW(), d.reason());
            case STOP -> stopIfRunning(d.reason());
            case NONE -> { /* hold */ }
        }
    }

    // ---- operations, each re-verifying live state immediately before acting ----

    /**
     * Start the miner and aim it at {@code target} (the ladder floor).
     *
     * <p><b>The change is recorded only once the miner is seen running.</b> A stopped Braiins miner
     * reports itself unreachable, and its API is also unreachable while BOSMiner boots — so at command
     * time "the start failed" and "the start worked, it is still coming up" look identical. Claiming
     * success then is wrong in two ways, both observed in production: the dashboard showed
     * "START off → 1200 W" for a start whose commands had actually failed with "No route to host", and
     * the phantom change reset {@code lastChangeAt}, so the restart cooldown blocked retries for six
     * minutes while the surplus climbed. Leaving it pending means an unlanded start is retried on the
     * next tick, and history only ever shows changes that really happened.
     */
    private void startAtMin(int target, String reason) {
        MinerStatus now = minerService.refresh();
        // Re-verify to avoid double-starting: skip only if it is genuinely up (reachable AND running).
        // We deliberately do NOT bail when unreachable — a stopped Braiins miner reports unreachable
        // but the start command still brings it up.
        if (isUp(now)) {
            log.info("autopilot: miner already running — skipping start");
            return;
        }
        minerService.setPowerTarget(target, true); // aim for the floor power target
        MinerStatus after = minerService.start();
        if (isUp(after)) {
            enforceTargetAndRecord(after, target, reason);
            return;
        }
        pendingStart = new PendingStart(target, reason, 0);
        log.info("autopilot: start issued but the miner is not up yet — will confirm on a later tick");
    }

    /** True when the miner is genuinely up: reachable AND its service running. */
    private static boolean isUp(MinerStatus st) {
        return st != null && st.reachable() && st.running();
    }

    /**
     * Once a pending start is seen running, enforce the target we asked for and record the change.
     *
     * <p>Enforcing matters because a miner that boots on its own comes up at <b>whatever target it had
     * before it stopped</b>, not the floor. In production a miner restarted at 2000 W (left from an
     * earlier step-down) while the autopilot believed it had started at 1200 W. With a healthy surplus
     * that was harmless, but the same sequence at a marginal surplus imports: the over-draw sits under
     * {@code emergencyGapW}, so the routine down-step is throttled and it persists for up to one
     * {@code downInterval}. Re-applying the floor costs a slower climb back, which the ramp-up path
     * exists to handle, and that is the right trade against silently drawing more than intended.
     */
    private void enforceTargetAndRecord(MinerStatus st, int target, String reason) {
        Integer actual = st != null ? st.powerTargetW() : null;
        if (actual != null && actual != target) {
            log.info("autopilot: miner came up at {}W, not the requested {}W — re-applying", actual, target);
            minerService.setPowerTarget(target, true);
        }
        recordChange("START", null, target, reason);
    }

    /**
     * Resolve a start we commanded but could not confirm at the time. Called once per tick with the
     * live status. Abandoned after {@link #MAX_START_CONFIRM_TICKS} so a start that never lands cannot
     * leave the autopilot waiting forever; the governor is free to decide START again in the meantime.
     */
    private void confirmPendingStart(MinerStatus st) {
        PendingStart p = pendingStart;
        if (p == null) return;
        if (isUp(st)) {
            pendingStart = null;
            enforceTargetAndRecord(st, p.targetW(), p.reason());
            return;
        }
        if (p.ticksWaited() + 1 >= MAX_START_CONFIRM_TICKS) {
            discardPendingStart("never confirmed after " + MAX_START_CONFIRM_TICKS + " ticks");
            return;
        }
        pendingStart = new PendingStart(p.targetW(), p.reason(), p.ticksWaited() + 1);
    }

    /** Forget a pending start we are no longer waiting on, saying why. */
    private void discardPendingStart(String why) {
        if (pendingStart == null) return;
        pendingStart = null;
        log.info("autopilot: dropping the unconfirmed start ({})", why);
    }

    private void setPowerIfMining(String action, int target, String reason) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (!MinerStatus.MINING.equals(now.state())) {
            log.info("autopilot: miner not mining ({}) — skipping power change", now.state());
            return;
        }
        Integer from = now.powerTargetW();
        MinerStatus after = minerService.setPowerTarget(target, true);
        // Record only if the miner didn't contradict us. It was reachable a moment ago, so the target
        // is read back immediately: if it comes back reachable and still reporting a different target,
        // the command did not apply and claiming otherwise would misreport history AND reset the
        // interval that paces the next step — delaying the retry of a change that silently failed.
        // An unreachable read-back is inconclusive rather than a failure (MinerService verifies on the
        // next poll), so it still records.
        if (after != null && after.reachable()
                && after.powerTargetW() != null && after.powerTargetW() != target) {
            log.warn("autopilot: {} to {}W did not apply (miner reports {}W) — not recording it",
                    action, target, after.powerTargetW());
            return;
        }
        recordChange(action, from, target, reason);
    }

    private void stopIfRunning(String reason) {
        MinerStatus now = minerService.refresh();
        if (now == null || !now.reachable()) return;
        if (!now.running()) {
            log.info("autopilot: miner already off — skipping stop");
            return;
        }
        Integer from = now.powerTargetW();
        minerService.stop();
        recordChange("STOP", from, null, reason);
    }

    private void recordChange(String action, Integer fromW, Integer toW, String detail) {
        lastChange.set(new AutopilotStatus.Change(Instant.now(), action, fromW, toW, detail));
    }
}
