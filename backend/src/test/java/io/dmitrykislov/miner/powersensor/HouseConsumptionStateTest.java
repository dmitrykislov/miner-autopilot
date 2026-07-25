package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HouseConsumptionStateTest {

    private HouseConsumptionState stateWithStaleAfter(int seconds) {
        var props = new HouseProperties(null,
                new HouseProperties.PowerSensor(true, "h", 1, 1, 1, seconds, "", 0.5), null, null, null);
        return new HouseConsumptionState(props);
    }

    @Test
    void emptyBeforeAnyReading() {
        var s = stateWithStaleAfter(30);
        assertThat(s.measuredKw()).isEmpty();
        assertThat(s.isMetered()).isFalse();
        assertThat(s.latest()).isNull();
    }

    @Test
    void freshReadingIsMeteredAndReturnsKw() {
        var s = stateWithStaleAfter(30);
        s.update(HousePower.measured(2500.0, 241.0, "clamp", Instant.now()));
        assertThat(s.isMetered()).isTrue();
        assertThat(s.measuredKw()).contains(2.5);
        assertThat(s.latest().powerW()).isEqualTo(2500.0);
    }

    @Test
    void staleReadingIsNotMetered() {
        var s = stateWithStaleAfter(30);
        s.update(HousePower.measured(2500.0, 241.0, "clamp", Instant.now().minusSeconds(120)));
        assertThat(s.isMetered()).isFalse();
        assertThat(s.measuredKw()).isEmpty();
        // the reading is still retained for inspection
        assertThat(s.latest()).isNotNull();
    }
}
