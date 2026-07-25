package io.dmitrykislov.miner.inverter;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HouseLoadStateTest {

    private HouseLoadState state(double seed) {
        var props = new HouseProperties(
                null,
                new HouseProperties.PowerSensor(true, "h", 1, 1, 1, 1, "", seed), null, null, null);
        return new HouseLoadState(props);
    }

    @Test
    void seedsFromConfiguredHouseLoad() {
        assertThat(state(2.0).get()).isEqualTo(2.0);
    }

    @Test
    void setUpdatesValue() {
        var s = state(0.5);
        s.set(3.7);
        assertThat(s.get()).isEqualTo(3.7);
    }

    @Test
    void negativeValuesAreClampedToZero() {
        var s = state(0.5);
        s.set(-10.0);
        assertThat(s.get()).isEqualTo(0.0);
    }
}
