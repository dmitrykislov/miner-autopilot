package io.dmitrykislov.miner.inverter;

import java.util.Map;

/**
 * Translates the WiNet-S i18n keys (e.g. {@code I18N_COMMON_DAILY_POWER_YIELD})
 * into friendly labels + a UI category. Anything unknown falls back to a
 * prettified version of the key so new firmware fields still render sensibly.
 */
public final class Labels {

    private Labels() {}

    private record Label(String text, String category) {}

    private static final Map<String, Label> METRIC = Map.ofEntries(
            Map.entry("I18N_COMMON_TOTAL_ACTIVE_POWER", new Label("Active Power", "power")),
            Map.entry("I18N_COMMON_TOTAL_DCPOWER", new Label("DC Power", "power")),
            Map.entry("I18N_COMMON_TOTAL_REACTIVE_POWER", new Label("Reactive Power", "power")),
            Map.entry("I18N_COMMON_TOTAL_APPARENT_POWER", new Label("Apparent Power", "power")),
            Map.entry("I18N_COMMON_MAXIMUM_APPARENT_POWER_SIWHFGQY", new Label("Max Apparent Power", "power")),
            Map.entry("I18N_COMMON_TOTAL_POWER_FACTOR", new Label("Power Factor", "power")),

            Map.entry("I18N_COMMON_DAILY_POWER_YIELD", new Label("Daily Yield", "energy")),
            Map.entry("I18N_COMMON_TOTAL_YIELD", new Label("Total Yield", "energy")),
            Map.entry("I18N_COMMON_TOTAL_GRID_RUNNING_TIME", new Label("Total Running Time", "energy")),

            Map.entry("I18N_COMMON_GRID_FREQUENCY", new Label("Grid Frequency", "grid")),
            Map.entry("I18N_COMMONUA", new Label("Phase Voltage (Ua)", "grid")),
            Map.entry("I18N_COMMON_FRAGMENT_RUN_TYPE1", new Label("Phase Current", "grid")),

            Map.entry("I18N_COMMON_BUS_VOLTAGE", new Label("Bus Voltage", "dc")),
            Map.entry("I18N_COMMON_SQUARE_ARRAY_INSULATION_IMPEDANCE", new Label("Array Insulation Resistance", "dc")),

            Map.entry("I18N_COMMON_AIR_TEM_INSIDE_MACHINE", new Label("Internal Temperature", "status")),
            Map.entry("I18N_COMMON_RUNNING_STATE", new Label("Running State", "status"))
    );

    /** Values that are themselves i18n keys (e.g. the running-state text). */
    private static final Map<String, String> VALUE = Map.ofEntries(
            Map.entry("I18N_COMMON_STANDBY", "Standby"),
            Map.entry("I18N_COMMON_RUNNING", "Running"),
            Map.entry("I18N_COMMON_ON_GRID_OPERATION", "On Grid"), // normal grid-connected operation
            Map.entry("I18N_COMMON_INITIAL_STANDBY", "Initial Standby"),
            Map.entry("I18N_COMMON_STARTUP", "Starting Up"),
            Map.entry("I18N_COMMON_SHUTDOWN", "Shutdown"),
            Map.entry("I18N_COMMON_FAULT", "Fault"),
            Map.entry("I18N_COMMON_MAINTAIN_MODE", "Maintenance"),
            Map.entry("I18N_COMMON_STATE_EMERGENCY_STOP", "Emergency Stop")
    );

    public static String label(String key) {
        Label l = METRIC.get(key);
        return l != null ? l.text() : prettify(key);
    }

    public static String category(String key) {
        Label l = METRIC.get(key);
        return l != null ? l.category() : "other";
    }

    public static String value(String raw) {
        if (raw == null) return null;
        String mapped = VALUE.get(raw);
        if (mapped != null) return mapped;
        // An unmapped value that is itself an i18n key (new firmware state, etc.) should still render
        // as friendly text — never the raw I18N_… key. Plain values (numbers, "--") pass through.
        return raw.startsWith("I18N_") ? prettify(raw) : raw;
    }

    /** Turn "I18N_CONFIG_KEY_1003332" / unknown keys into "Config Key 1003332". */
    static String prettify(String key) {
        if (key == null || key.isBlank()) return "";
        String s = key;
        if (s.startsWith("I18N_")) s = s.substring(5);
        s = s.replace("COMMON_", "").replace("CONFIG_", "").replace('_', ' ').trim();
        if (s.isEmpty()) return key;
        String[] parts = s.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }
}
