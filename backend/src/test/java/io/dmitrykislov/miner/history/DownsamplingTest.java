package io.dmitrykislov.miner.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class DownsamplingTest {

    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    private TelemetrySample s(long sec, Double solar, Double cons, Integer pow, Integer draw, String state) {
        return new TelemetrySample(T0.plusSeconds(sec), solar, cons, pow, draw, state);
    }

    @Test void returnsInputUnchangedWhenUnderTheCap() {
        List<TelemetrySample> in = List.of(s(0, 1.0, null, null, null, "MINING"),
                s(60, 2.0, null, null, null, "MINING"));
        assertThat(Downsampling.reduce(in, 10)).isSameAs(in);
    }

    @Test void averagesEachBucketAndHalvesTheCount() {
        List<TelemetrySample> in = new ArrayList<>();
        for (int i = 0; i < 100; i++) in.add(s(i * 60, (double) i, (double) (i * 2), i, i + 1, "MINING"));
        List<TelemetrySample> out = Downsampling.reduce(in, 50);
        assertThat(out).hasSize(50);
        // First bucket = samples 0,1 → solar mean 0.5, consumption mean 1.0, power mean 0.5→1, draw mean 1.5→2.
        assertThat(out.get(0).solarW()).isCloseTo(0.5, within(1e-9));
        assertThat(out.get(0).consumptionW()).isCloseTo(1.0, within(1e-9));
        assertThat(out.get(0).minerPowerW()).isEqualTo(1);   // round(0.5)
        assertThat(out.get(0).minerDrawW()).isEqualTo(2);    // round(1.5)
        // Bucket timestamp is the mean instant of its members (samples 0 and 1 → 30 s).
        assertThat(out.get(0).at()).isEqualTo(T0.plusSeconds(30));
    }

    @Test void nullFieldsAreIgnoredInTheAverageAndStayNullWhenWholeBucketIsNull() {
        List<TelemetrySample> in = List.of(
                s(0, 100.0, null, null, null, null),
                s(60, null, null, null, null, null),   // solar null here
                s(120, 200.0, null, null, null, null),
                s(180, null, null, null, null, null));
        List<TelemetrySample> out = Downsampling.reduce(in, 2);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).solarW()).isCloseTo(100.0, within(1e-9)); // only the non-null (100) counted
        assertThat(out.get(0).consumptionW()).isNull();                 // whole bucket null → null
        assertThat(out.get(1).solarW()).isCloseTo(200.0, within(1e-9));
    }

    @Test void minerStateTakesTheLastNonNullInTheBucket() {
        List<TelemetrySample> in = List.of(
                s(0, 1.0, null, null, null, "MINING"),
                s(60, 1.0, null, null, null, "SUSPENDED"),
                s(120, 1.0, null, null, null, null)); // trailing null must not clobber the state
        List<TelemetrySample> out = Downsampling.reduce(in, 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).minerState()).isEqualTo("SUSPENDED");
    }

    @Test void everyOutputBucketIsNonEmptyAndOrderPreserved() {
        List<TelemetrySample> in = new ArrayList<>();
        for (int i = 0; i < 1000; i++) in.add(s(i, (double) i, null, null, null, "MINING"));
        List<TelemetrySample> out = Downsampling.reduce(in, 137);
        assertThat(out).hasSize(137);
        for (int i = 1; i < out.size(); i++) {
            assertThat(out.get(i).at()).isAfter(out.get(i - 1).at());     // time-ascending preserved
            assertThat(out.get(i).solarW()).isGreaterThan(out.get(i - 1).solarW()); // monotone data stays monotone
        }
    }

    @Test void rejectsNonPositiveMaxPoints() {
        assertThatThrownBy(() -> Downsampling.reduce(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
