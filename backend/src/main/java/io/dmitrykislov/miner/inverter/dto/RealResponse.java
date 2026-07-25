package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code result_data} payload of the {@code real} service — the inverter's
 * real-time measurement snapshot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RealResponse(
        // echoes the requested service name, always "real"
        @JsonProperty("service") String service,
        // all real-time readings for the device (~22 entries for the SG10RS)
        @JsonProperty("list") List<RealPoint> list) {

    public List<RealPoint> list() {
        return list == null ? List.of() : list;
    }
}
