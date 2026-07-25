package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** {@code result_data} payload of the {@code devicelist} service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceListResponse(
        // echoes the requested service name, always "devicelist"
        @JsonProperty("service") String service,
        // attached devices (one SG10RS here)
        @JsonProperty("list") List<DeviceEntry> list,
        // number of devices
        @JsonProperty("count") Integer count) {

    public List<DeviceEntry> list() {
        return list == null ? List.of() : list;
    }
}
