package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PowerSensorClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HouseProperties.PowerSensor cfg =
            new HouseProperties.PowerSensor(true, "h", 49476, 180, 90, 30, "", 0.5);

    private JsonNode json(String s) { return mapper.readTree(s); }

    @Test
    void parsesClampReadingAsWholeHomeWatts() {
        var p = PowerSensorClient.parse(json("""
            {"type":"instant_power","power":895.0,"unit":"w","voltage":null,"mac":"ecda3ba52594"}
            """), cfg);
        assertThat(p).isNotNull();
        assertThat(p.kind()).isEqualTo(PowerSensorClient.Kind.CLAMP);
        assertThat(p.watts()).isEqualTo(895.0);
        assertThat(p.voltage()).isNull();
        assertThat(p.mac()).isEqualTo("ecda3ba52594");
    }

    @Test
    void parsesGatewayReadingWithVoltage() {
        var p = PowerSensorClient.parse(json("""
            {"type":"instant_power","power":1.68,"unit":"w","voltage":241.5,"mac":"5443b27fc72c"}
            """), cfg);
        assertThat(p.kind()).isEqualTo(PowerSensorClient.Kind.GATEWAY);
        assertThat(p.voltage()).isCloseTo(241.5, within(1e-9));
    }

    @Test
    void convertsRawMicroampUnitToWatts() {
        var p = PowerSensorClient.parse(json("""
            {"type":"instant_power","power":193.0,"unit":"u","voltage":null,"mac":"ecda3ba52594"}
            """), cfg);
        assertThat(p.watts()).isCloseTo(10.0, within(1e-6)); // 193 / 19.3
    }

    @Test
    void ignoresNonInstantPowerAndMissingPower() {
        assertThat(PowerSensorClient.parse(json("{\"type\":\"status\",\"foo\":1}"), cfg)).isNull();
        assertThat(PowerSensorClient.parse(json("{\"type\":\"instant_power\",\"mac\":\"x\"}"), cfg)).isNull();
        assertThat(PowerSensorClient.parse(null, cfg)).isNull();
    }

    @Test
    void honoursExplicitClampMac() {
        var explicit = new HouseProperties.PowerSensor(true, "h", 1, 1, 1, 1, "5443b27fc72c", 0.5);
        var p = PowerSensorClient.parse(json("""
            {"type":"instant_power","power":1.6,"unit":"w","voltage":241.5,"mac":"5443b27fc72c"}
            """), explicit);
        assertThat(p.kind()).isEqualTo(PowerSensorClient.Kind.CLAMP); // matched by mac despite having voltage
    }
}
