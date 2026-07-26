package io.dmitrykislov.miner.solaranalytics;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of the Solar Analytics /live_site_data response (newest consumed watts). */
class SolarAnalyticsClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void extractsNewestConsumedWatts() {
        var j = mapper.readTree("{\"data\":["
                + "{\"consumed\":903,\"generated\":200,\"t_stamp\":\"2026-07-26T06:36:30Z\"},"
                + "{\"consumed\":958,\"generated\":221,\"t_stamp\":\"2026-07-26T06:37:05Z\"}],"
                + "\"site_timezone\":\"Australia/Brisbane\"}");
        assertThat(SolarAnalyticsClient.latestConsumedWatts(j)).isEqualTo(958.0);
    }

    @Test
    void nullWhenNoData() {
        assertThat(SolarAnalyticsClient.latestConsumedWatts(mapper.readTree("{\"data\":[]}"))).isNull();
        assertThat(SolarAnalyticsClient.latestConsumedWatts(mapper.readTree("{}"))).isNull();
    }

    @Test
    void nullWhenLatestSampleHasNoConsumed() {
        var j = mapper.readTree("{\"data\":[{\"generated\":200,\"t_stamp\":\"2026-07-26T06:36:30Z\"}]}");
        assertThat(SolarAnalyticsClient.latestConsumedWatts(j)).isNull();
    }
}
