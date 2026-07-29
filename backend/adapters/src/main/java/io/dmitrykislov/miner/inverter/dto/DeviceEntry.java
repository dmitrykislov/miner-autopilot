package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One device from the {@code devicelist} service. For this site there is a
 * single entry: the SG10RS on COM1, address 1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceEntry(
        // logical device id used in subsequent real/direct requests (1)
        @JsonProperty("dev_id") int devId,
        // device type code; 21 = SG-RS string inverter family
        @JsonProperty("dev_type") int devType,
        // Sungrow internal device code (9737 for this unit)
        @JsonProperty("dev_code") int devCode,
        // model name, e.g. "SG10RS"
        @JsonProperty("dev_model") String devModel,
        // full display name, e.g. "SG10RS(COM1-001)"
        @JsonProperty("dev_name") String devName,
        // inverter serial number
        @JsonProperty("dev_sn") String devSn,
        // RS485 bus port name, e.g. "COM1"
        @JsonProperty("port_name") String portName,
        // physical Modbus address on the bus ("1")
        @JsonProperty("phys_addr") String physAddr,
        // 1 = device is communicating, 0 = link down
        @JsonProperty("link_status") int linkStatus) {}
