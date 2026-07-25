package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.inverter.dto.DirectResponse;
import io.dmitrykislov.miner.inverter.dto.MpptEntry;
import io.dmitrykislov.miner.inverter.dto.RealPoint;
import io.dmitrykislov.miner.inverter.dto.RealResponse;
import io.dmitrykislov.miner.inverter.model.DeviceInfo;
import io.dmitrykislov.miner.inverter.model.InverterSnapshot;
import io.dmitrykislov.miner.inverter.model.Metric;
import io.dmitrykislov.miner.inverter.model.MpptString;
import io.dmitrykislov.miner.inverter.model.PowerBalance;
import io.dmitrykislov.miner.util.Rounding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mapping from the dongle's raw {@code real}/{@code direct} datasets to an
 * {@link InverterSnapshot}. Kept free of I/O and side effects so it can be unit
 * tested exhaustively.
 */
public final class SnapshotMapper {

    private SnapshotMapper() {}

    static final String KEY_RUNNING_STATE = "I18N_COMMON_RUNNING_STATE";
    static final String KEY_ACTIVE_POWER = "I18N_COMMON_TOTAL_ACTIVE_POWER";

    /** i18n metric key -> highlight field name used by headline UI tiles. */
    static final Map<String, String> HIGHLIGHT_KEYS = Map.of(
            KEY_ACTIVE_POWER, "activePowerKw",
            "I18N_COMMON_TOTAL_DCPOWER", "dcPowerKw",
            "I18N_COMMON_DAILY_POWER_YIELD", "dailyYieldKwh",
            "I18N_COMMON_TOTAL_YIELD", "totalYieldKwh",
            "I18N_COMMON_GRID_FREQUENCY", "gridFrequencyHz",
            "I18N_COMMON_AIR_TEM_INSIDE_MACHINE", "temperatureC",
            "I18N_COMMONUA", "phaseVoltageV",
            "I18N_COMMON_TOTAL_POWER_FACTOR", "powerFactor"
    );

    public static InverterSnapshot map(DeviceInfo dev, RealResponse real, DirectResponse direct,
                                       Double houseKw, Instant now) {
        List<Metric> metrics = new ArrayList<>();
        Map<String, Object> highlights = new LinkedHashMap<>();
        String runningState = "Unknown";
        double solarPowerKw = 0.0;

        if (real != null) {
            for (RealPoint p : real.list()) {
                String key = p.dataName();
                String value = Labels.value(p.dataValue());

                metrics.add(new Metric(key, Labels.label(key), value, p.dataUnit(), Labels.category(key)));

                if (KEY_RUNNING_STATE.equals(key)) {
                    runningState = value;
                }
                if (KEY_ACTIVE_POWER.equals(key)) {
                    solarPowerKw = parseOrZero(p.dataValue());
                }
                String hk = HIGHLIGHT_KEYS.get(key);
                if (hk != null) {
                    Double num = parseNumber(p.dataValue());
                    if (num != null) highlights.put(hk, num);
                }
            }
        }

        List<MpptString> strings = new ArrayList<>();
        if (direct != null) {
            for (MpptEntry e : direct.list()) {
                double v = parseOrZero(e.voltage());
                double a = parseOrZero(e.current());
                strings.add(new MpptString(
                        e.name() != null ? e.name() : "MPPT",
                        v, a, Rounding.toPlaces(v * a / 1000.0, 3)));
            }
        }

        // Solar-vs-house margin. Solar is always measured by the inverter; house
        // load is measured by the Powersensor when live (houseKw != null), else the
        // margin is unavailable. See PowerBalance for semantics.
        PowerBalance balance = houseKw != null
                ? PowerBalance.metered(solarPowerKw, houseKw)
                : PowerBalance.unmetered(solarPowerKw);

        return new InverterSnapshot(true, dev.model(), dev.serialNumber(),
                runningState, now, highlights, balance, metrics, strings, null);
    }

    static Double parseNumber(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.equals("--")) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static double parseOrZero(String s) {
        Double d = parseNumber(s);
        return d == null ? 0.0 : d;
    }
}
