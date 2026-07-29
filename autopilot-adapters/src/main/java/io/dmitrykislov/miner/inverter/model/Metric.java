package io.dmitrykislov.miner.inverter.model;

/**
 * A single real-time reading from the inverter.
 *
 * @param key      raw i18n key from the dongle (e.g. I18N_COMMON_DAILY_POWER_YIELD)
 * @param label    human-friendly label resolved from {@code key}
 * @param value    the reading as reported ("40.9", "Standby", "--")
 * @param unit     unit of measure ("kWh", "V", "℃", "")
 * @param category grouping used by the UI ("power", "energy", "grid", "dc", "status", "other")
 */
public record Metric(String key, String label, String value, String unit, String category) {}
