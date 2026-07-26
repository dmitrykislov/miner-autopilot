package io.dmitrykislov.miner.solaranalytics;

import io.dmitrykislov.miner.config.HouseProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HouseConsumptionStateTest {

    private HouseConsumptionState stateWithStaleAfter(int seconds) {
        var props = new HouseProperties(null,
                new HouseProperties.SolarAnalytics(true, "h", "u", "p", "site", 15000, seconds, 8000),
                null, null, null);
        return new HouseConsumptionState(props);
    }

    @Test
    void emptyBeforeAnyReading() {
        var s = stateWithStaleAfter(60);
        assertThat(s.measuredKw()).isEmpty();
        assertThat(s.latest()).isNull();
    }

    @Test
    void freshReadingIsMeteredAndReturnsKw() {
        var s = stateWithStaleAfter(60);
        s.update(HousePower.measured(2500.0, null, "12345", Instant.now()));
        assertThat(s.measuredKw()).contains(2.5);
        assertThat(s.latest().powerW()).isEqualTo(2500.0);
    }

    @Test
    void staleReadingIsNotMetered() {
        var s = stateWithStaleAfter(60);
        s.update(HousePower.measured(2500.0, null, "12345", Instant.now().minusSeconds(120)));
        assertThat(s.measuredKw()).isEmpty();
        assertThat(s.latest()).isNotNull(); // still retained for inspection
    }
}
