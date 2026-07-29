package io.dmitrykislov.miner;

import io.dmitrykislov.miner.port.ConsumptionSource;
import io.dmitrykislov.miner.port.PowerReading;
import io.dmitrykislov.miner.port.SolarSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the optional HTTP ingest: with house.ingest.enabled=true the endpoint is
 * wired, and a real POST feeds the SolarSource / ConsumptionSource ports (and /clear empties them).
 * The built-in solar/consumption adapters are turned off so only the HTTP pushes reach the ports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "house.ingest.enabled=true",
        "auth.enabled=false",
        "house.inverter.enabled=false",          // don't let the built-in solar also write the port
        "house.solar-analytics.enabled=false",   // ditto for consumption
        "house.autopilot.enabled=false",
})
class IngestEndpointTest {

    @Value("${local.server.port}") int port;
    @Autowired SolarSource solar;
    @Autowired ConsumptionSource consumption;

    @Test
    void postingReadingsFeedsThePortsAndClearEmptiesThem() throws Exception {
        assertThat(post("/api/ingest/solar?watts=4200")).isEqualTo(202);
        assertThat(post("/api/ingest/consumption?watts=900")).isEqualTo(202);

        assertThat(solar.latest()).map(PowerReading::watts).contains(4200.0);
        assertThat(consumption.latest()).map(PowerReading::watts).contains(900.0);

        assertThat(post("/api/ingest/solar/clear")).isEqualTo(202);
        assertThat(solar.latest()).isEmpty();
    }

    private int post(String path) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.statusCode();
    }
}
