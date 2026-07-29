package io.dmitrykislov.miner.port;

import java.time.Instant;

/** A single power measurement in watts at an instant — emitted by a solar or consumption source. */
public record PowerReading(Instant at, double watts) {}
