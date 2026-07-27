package io.dmitrykislov.miner.autopilot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Tests for the averaged solar/consumption → margin signals. */
class EnergyAveragesTest {

    private static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");

    private EnergyAverages avg() {
        return new EnergyAverages(Duration.ofMinutes(3), Duration.ofMinutes(15), Duration.ofSeconds(90));
    }

    private Instant at(long sec) {
        return T0.plusSeconds(sec);
    }

    @Test void marginIsSolarMinusConsumption() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        assertThat(a.marginAvg(at(5), Duration.ofMinutes(3)).getAsDouble()).isCloseTo(2500, within(1e-6));
    }

    @Test void marginEmptyWhenConsumptionMissing() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000); // no consumption yet
        assertThat(a.marginAvg(at(5), Duration.ofMinutes(3))).isEmpty();
    }

    @Test void marginEmptyWhenSolarMissing() {
        EnergyAverages a = avg();
        a.recordConsumption(at(0), 1500);
        assertThat(a.marginAvg(at(5), Duration.ofMinutes(3))).isEmpty();
    }

    @Test void marginEmptyWhenAFeedGoesStale() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 4000);
        a.recordConsumption(at(0), 1500);
        // 91 s later both feeds exceed the 90 s freshness bound → unknown
        assertThat(a.marginAvg(at(91), Duration.ofMinutes(3))).isEmpty();
    }

    @Test void shortAndLongWindowsDifferOnATrend() {
        EnergyAverages a = avg();
        // consumption flat 1000; solar ramps up 1000→4000 over 15 min
        for (int s = 0; s <= 900; s += 30) {
            a.recordSolar(at(s), 1000 + (3000.0 * s / 900));
            a.recordConsumption(at(s), 1000);
        }
        double shortM = a.marginAvg(at(900), Duration.ofMinutes(3)).getAsDouble();
        double longM = a.marginAvg(at(900), Duration.ofMinutes(15)).getAsDouble();
        assertThat(shortM).isGreaterThan(longM); // recent (higher solar) margin exceeds the long average
    }

    @Test void signalsBundleShortAndLong() {
        EnergyAverages a = avg();
        a.recordSolar(at(0), 5000);
        a.recordConsumption(at(0), 1000);
        var sig = a.signals(at(5));
        assertThat(sig.shortMarginW().getAsDouble()).isCloseTo(4000, within(1e-6));
        assertThat(sig.longMarginW().getAsDouble()).isCloseTo(4000, within(1e-6));
        assertThat(sig.solarShortW().getAsDouble()).isCloseTo(5000, within(1e-6));
        assertThat(sig.consumptionShortW().getAsDouble()).isCloseTo(1000, within(1e-6));
    }

    @Test void rejectsShortWindowLongerThanLong() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new EnergyAverages(Duration.ofMinutes(15), Duration.ofMinutes(3), Duration.ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
