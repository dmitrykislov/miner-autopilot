package io.dmitrykislov.miner.port;

/**
 * One cooling fan on the miner.
 *
 * @param name         fan label/identifier
 * @param rpm          measured speed in revolutions per minute
 * @param speedPercent commanded speed as a percentage (0–100)
 */
public record Fan(String name, int rpm, int speedPercent) {}
