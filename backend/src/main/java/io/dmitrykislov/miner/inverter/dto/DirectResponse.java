package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** {@code result_data} payload of the {@code direct} service — DC/MPPT inputs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DirectResponse(
        // echoes the requested service name, always "direct"
        @JsonProperty("service") String service,
        // one entry per MPPT tracker
        @JsonProperty("list") List<MpptEntry> list,
        // number of trackers reported
        @JsonProperty("count") Integer count) {

    public List<MpptEntry> list() {
        return list == null ? List.of() : list;
    }
}
