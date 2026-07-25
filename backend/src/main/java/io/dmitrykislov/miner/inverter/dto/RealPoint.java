package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry in the {@code real} service response list — the raw shape the
 * SG10RS/WiNet-S sends for each real-time reading.
 *
 * <p>Every reading arrives as this same 3-field triple; the meaning is carried
 * by {@link #dataName}. The known {@code dataName} values observed from this
 * SG10RS (dev_type 21), with their unit and meaning:
 *
 * <pre>
 * I18N_COMMON_TOTAL_GRID_RUNNING_TIME          h     Lifetime hours feeding the grid
 * I18N_COMMON_DAILY_POWER_YIELD                kWh   Energy generated so far today
 * I18N_COMMON_TOTAL_YIELD                      kWh   Lifetime energy generated
 * I18N_COMMON_RUNNING_STATE                    -     State (value is itself an i18n key, e.g. *_STANDBY)
 * I18N_COMMON_BUS_VOLTAGE                      V     Internal DC bus voltage
 * I18N_COMMON_AIR_TEM_INSIDE_MACHINE           ℃     Internal ambient temperature
 * I18N_COMMON_SQUARE_ARRAY_INSULATION_IMPEDANCE kΩ   PV array insulation resistance to ground
 * I18N_COMMON_TOTAL_DCPOWER                    kW    Total DC power drawn from the panels
 * I18N_COMMON_TOTAL_ACTIVE_POWER               kW    AC active power produced (== solar generation)
 * I18N_COMMON_TOTAL_REACTIVE_POWER             kvar  AC reactive power
 * I18N_COMMON_TOTAL_APPARENT_POWER             kVA   AC apparent power
 * I18N_COMMON_TOTAL_POWER_FACTOR               -     Power factor (-1..1)
 * I18N_COMMON_GRID_FREQUENCY                   Hz    Grid frequency
 * I18N_COMMONUA                                V     Phase A voltage (single-phase SG10RS)
 * I18N_COMMON_FRAGMENT_RUN_TYPE1               A     Phase A current
 * I18N_COMMON_MAXIMUM_APPARENT_POWER_SIWHFGQY  kVA   Rated max apparent power (10 kVA)
 * I18N_CONFIG_KEY_1003332 / _1003334 / _1003336 V   Per-phase grid voltage (A/B/C; B,C "--" on 1-phase)
 * I18N_CONFIG_KEY_1003331 / _1003333 / _1003335 A   Per-phase grid current (A/B/C)
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RealPoint(
        // i18n key naming the measurement (see table above)
        @JsonProperty("data_name") String dataName,
        // the reading as a string: numeric ("40.9"), an i18n state key, or "--" when unavailable
        @JsonProperty("data_value") String dataValue,
        // unit of measure ("kWh", "V", "kW", "℃", "Hz", ""); may be empty
        @JsonProperty("data_unit") String dataUnit) {}
