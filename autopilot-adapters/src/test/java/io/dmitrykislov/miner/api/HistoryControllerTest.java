package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.history.HistoryProperties;
import io.dmitrykislov.miner.history.HistoryResponse;
import io.dmitrykislov.miner.port.PowerChangeEvent;
import io.dmitrykislov.miner.port.TelemetrySample;
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
        when(store.eventsBetween(any(), any())).thenReturn(List.of());
        when(store.samplesBetween(any(), any())).thenReturn(List.of());
        controller = new HistoryController(store, new HistoryProperties(true, "data", 60_000, 31));
    }

    private long windowHours(HistoryResponse r) {
        return Duration.between(r.from(), r.to()).toHours();
    }

    @Test void hoursGivesTheLastNHoursEndingNow() {
        Instant before = Instant.now();
        HistoryResponse r = controller.history(null, null, 8);
        assertThat(windowHours(r)).isEqualTo(8);
        assertThat(r.to()).isBetween(before, Instant.now()); // "to" is ~now
        assertThat(r.retentionDays()).isEqualTo(31);
        assertThat(r.intervalMs()).isEqualTo(60_000);
    }

    @Test void defaultsTo12hWhenNothingSpecified() {
        assertThat(windowHours(controller.history(null, null, null))).isEqualTo(12);
    }

    @Test void nonPositiveHoursClampToOne() {
        assertThat(windowHours(controller.history(null, null, 0))).isEqualTo(1);
        assertThat(windowHours(controller.history(null, null, -5))).isEqualTo(1);
    }

    @Test void explicitFromToWindowIsHonoured() {
        // millis precision — the API is epoch-millis in/out.
        Instant to = Instant.ofEpochMilli(Instant.now().minus(Duration.ofHours(2)).toEpochMilli());
        Instant from = to.minus(Duration.ofHours(4));
        HistoryResponse r = controller.history(from.toEpochMilli(), to.toEpochMilli(), null);
        assertThat(r.from()).isEqualTo(from);
        assertThat(r.to()).isEqualTo(to);
    }

    @Test void clampsFutureToDownToNow() {
        Instant future = Instant.now().plus(Duration.ofDays(2));
        HistoryResponse r = controller.history(null, future.toEpochMilli(), 1);
        assertThat(r.to()).isBeforeOrEqualTo(Instant.now());
    }

    @Test void clampsFromBackToTheRetentionWindow() {
        Instant wayBack = Instant.now().minus(Duration.ofDays(365));
        HistoryResponse r = controller.history(wayBack.toEpochMilli(), null, null);
        // from can't be older than now − retention (31 days).
        assertThat(r.from()).isAfterOrEqualTo(Instant.now().minus(Duration.ofDays(31)).minusSeconds(2));
    }

    @Test void guaranteesFromStrictlyBeforeTo() {
        Instant t = Instant.now().minus(Duration.ofHours(1));
        // from == to → controller must still produce from < to.
        HistoryResponse r = controller.history(t.toEpochMilli(), t.toEpochMilli(), null);
        assertThat(r.from()).isBefore(r.to());
    }

    @Test void downsamplesLargeResultSets() {
        List<TelemetrySample> many = new ArrayList<>();
        Instant t0 = Instant.now().minus(Duration.ofHours(20));
        for (int i = 0; i < 5000; i++) {
            many.add(new TelemetrySample(t0.plusSeconds(i * 12L), (double) i, null, null, null, "MINING"));
        }
        when(store.samplesBetween(any(), any())).thenReturn(many);
        HistoryResponse r = controller.history(null, null, 20);
        assertThat(r.samples()).hasSizeLessThanOrEqualTo(1500).isNotEmpty();
    }

    @Test void passesEventsThroughUnchanged() {
        var e = new PowerChangeEvent(Instant.now(), "STOP", 2400, null, "cloud");
        when(store.eventsBetween(any(), any())).thenReturn(List.of(e));
        assertThat(controller.history(null, null, 24).events()).containsExactly(e);
    }

    @Test void queriesTheStoreForTheComputedWindow() {
        controller.history(null, null, 6);
        verify(store).samplesBetween(any(), any());
        verify(store).eventsBetween(any(), any());
    }
}
