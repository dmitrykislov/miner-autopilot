package io.dmitrykislov.miner.inverter.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the DTOs deserialize the WiNet-S wire format under Jackson 3
 * (Spring Boot 4). Payloads mirror real captures from the SG10RS.
 */
class WiNetDtoDeserializationTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void deserializesDeviceListResultData() {
        String json = """
            {"service":"devicelist","list":[
              {"id":1,"dev_id":1,"dev_code":9737,"dev_type":21,"dev_procotol":2,
               "dev_sn":"A24A0965660","dev_name":"SG10RS(COM1-001)","dev_model":"SG10RS",
               "port_name":"COM1","phys_addr":"1","link_status":1,"init_status":1,"list":[]}],
             "count":1}
            """;
        DeviceListResponse r = mapper.readValue(json, DeviceListResponse.class);
        assertThat(r.list()).hasSize(1);
        DeviceEntry d = r.list().get(0);
        assertThat(d.devId()).isEqualTo(1);
        assertThat(d.devType()).isEqualTo(21);
        assertThat(d.devCode()).isEqualTo(9737);
        assertThat(d.devModel()).isEqualTo("SG10RS");
        assertThat(d.devSn()).isEqualTo("A24A0965660");
        assertThat(d.portName()).isEqualTo("COM1");
        assertThat(d.linkStatus()).isEqualTo(1);
    }

    @Test
    void deserializesRealResultData() {
        String json = """
            {"service":"real","list":[
              {"data_name":"I18N_COMMON_DAILY_POWER_YIELD","data_value":"40.9","data_unit":"kWh"},
              {"data_name":"I18N_COMMON_TOTAL_ACTIVE_POWER","data_value":"1.23","data_unit":"kW"},
              {"data_name":"I18N_CONFIG_KEY_1003334","data_value":"--","data_unit":"V"}]}
            """;
        RealResponse r = mapper.readValue(json, RealResponse.class);
        assertThat(r.list()).hasSize(3);
        RealPoint first = r.list().get(0);
        assertThat(first.dataName()).isEqualTo("I18N_COMMON_DAILY_POWER_YIELD");
        assertThat(first.dataValue()).isEqualTo("40.9");
        assertThat(first.dataUnit()).isEqualTo("kWh");
        assertThat(r.list().get(2).dataValue()).isEqualTo("--");
    }

    @Test
    void deserializesDirectResultData() {
        String json = """
            {"service":"direct","list":[
              {"name":"MPPT1","voltage":"580.0","voltage_unit":"V","current":"5.0","current_unit":"A"},
              {"name":"MPPT2","voltage":"0.0","voltage_unit":"V","current":"0.0","current_unit":"A"}],
             "count":2}
            """;
        DirectResponse r = mapper.readValue(json, DirectResponse.class);
        assertThat(r.list()).hasSize(2);
        MpptEntry m = r.list().get(0);
        assertThat(m.name()).isEqualTo("MPPT1");
        assertThat(m.voltage()).isEqualTo("580.0");
        assertThat(m.current()).isEqualTo("5.0");
        assertThat(r.count()).isEqualTo(2);
    }

    @Test
    void nullListsBecomeEmptyNotNull() {
        RealResponse r = mapper.readValue("{\"service\":\"real\"}", RealResponse.class);
        assertThat(r.list()).isEmpty();
        DirectResponse d = mapper.readValue("{\"service\":\"direct\"}", DirectResponse.class);
        assertThat(d.list()).isEmpty();
        DeviceListResponse dl = mapper.readValue("{\"service\":\"devicelist\"}", DeviceListResponse.class);
        assertThat(dl.list()).isEmpty();
    }

    @Test
    void ignoresUnknownFields() {
        // Firmware may add fields; DTOs must not choke on them.
        String json = "{\"service\":\"real\",\"future_field\":123,\"list\":[]}";
        RealResponse r = mapper.readValue(json, RealResponse.class);
        assertThat(r.service()).isEqualTo("real");
    }

    @Test
    void envelopeParsesCodesAndSuccessFlag() {
        WiNetEnvelope ok = mapper.readValue(
                "{\"result_code\":1,\"result_msg\":\"success\",\"result_data\":{\"service\":\"real\"}}",
                WiNetEnvelope.class);
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.resultData().path("service").asText()).isEqualTo("real");

        WiNetEnvelope expired = mapper.readValue(
                "{\"result_code\":106,\"result_msg\":\"I18N_COMMON_ACCOUNT_OUT_FRESH\",\"result_data\":{}}",
                WiNetEnvelope.class);
        assertThat(expired.isSuccess()).isFalse();
        assertThat(expired.resultCode()).isEqualTo(WiNetEnvelope.SESSION_EXPIRED);
    }
}
