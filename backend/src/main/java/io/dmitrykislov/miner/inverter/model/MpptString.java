package io.dmitrykislov.miner.inverter.model;

/** One DC input (MPPT) string as reported by the {@code direct} service. */
public record MpptString(String name, double voltage, double current, double powerKw) {}
