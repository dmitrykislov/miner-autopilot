package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One DC input (MPPT tracker) entry from the {@code direct} service. The SG10RS
 * reports three trackers (MPPT1..3); unused ones read 0 V / 0 A.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MpptEntry(
        // tracker label, e.g. "MPPT1"
        @JsonProperty("name") String name,
        // DC string voltage as a string, e.g. "25.3"
        @JsonProperty("voltage") String voltage,
        // unit for voltage, normally "V"
        @JsonProperty("voltage_unit") String voltageUnit,
        // DC string current as a string, e.g. "0.0"
        @JsonProperty("current") String current,
        // unit for current, normally "A"
        @JsonProperty("current_unit") String currentUnit) {}
