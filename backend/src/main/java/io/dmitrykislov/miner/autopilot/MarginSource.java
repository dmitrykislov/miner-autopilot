package io.dmitrykislov.miner.autopilot;

import java.util.OptionalDouble;

/**
 * Supplies the current power margin (solar − whole-home consumption) in watts.
 * Abstracted so the autopilot loop can be driven from live device data in
 * production or from a simulator in tests. Returns empty when the margin is
 * currently unknown — inverter offline, or consumption unavailable/stale/gated,
 * or the snapshot itself stale — in which case the autopilot treats it as a
 * safety condition and stops a running miner (see {@code MinerAutopilot}).
 */
public interface MarginSource {
    OptionalDouble currentMarginWatts();
}
