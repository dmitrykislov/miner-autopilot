package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.history.HistoryProperties;
import io.dmitrykislov.miner.history.HistoryResponse;
import io.dmitrykislov.miner.history.PowerChangeEvent;
import io.dmitrykislov.miner.history.TelemetrySample;
import io.dmitrykislov.miner.history.TelemetryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoryControllerTest {

    private TelemetryStore store;
    private HistoryController controller;

    @BeforeEach
    void setup() {
        store = mock(TelemetryStore.class);
        when(store.eventsSince(any())).thenReturn(List.of());
        when(store.samplesSince(any())).thenReturn(List.of());
        controller = new HistoryController(store, new HistoryProperties(true, "data", 60_000, 31));
    }

    private long windowHours(HistoryResponse r) {
        return Duration.between(r.from(), r.to()).toHours();
    }

    @Test void defaultsToA24hWindow() {
        HistoryResponse r = controller.history(24);
        assertThat(windowHours(r)).isEqualTo(24);
        assertThat(r.retentionDays()).isEqualTo(31);
        assertThat(r.intervalMs()).isEqualTo(60_000);
    }

    @Test void clampsHoursToTheRetentionWindow() {
        HistoryResponse r = controller.history(24 * 365); // ask for a year
        assertThat(windowHours(r)).isEqualTo(31 * 24);    // clamped to retention (31 days)
    }

    @Test void clampsNonPositiveHoursToAtLeastOne() {
        assertThat(windowHours(controller.history(0))).isEqualTo(1);
        assertThat(windowHours(controller.history(-5))).isEqualTo(1);
    }

    @Test void downsamplesLargeResultSets() {
        List<TelemetrySample> many = new ArrayList<>();
        Instant t0 = Instant.now().minus(Duration.ofDays(7));
        for (int i = 0; i < 5000; i++) {
            many.add(new TelemetrySample(t0.plusSeconds(i * 60L), (double) i, null, null, null, "MINING"));
        }
        when(store.samplesSince(any())).thenReturn(many);

        HistoryResponse r = controller.history(24 * 7);
        assertThat(r.samples()).hasSizeLessThanOrEqualTo(1500); // capped by the controller
        assertThat(r.samples()).isNotEmpty();
    }

    @Test void passesEventsThroughUnchanged() {
        var e = new PowerChangeEvent(Instant.now(), "STOP", 2400, null, "cloud");
        when(store.eventsSince(any())).thenReturn(List.of(e));
        assertThat(controller.history(24).events()).containsExactly(e);
    }

    @Test void queriesTheStoreForTheComputedWindow() {
        controller.history(6);
        verify(store).samplesSince(any());
        verify(store).eventsSince(any());
    }
}
