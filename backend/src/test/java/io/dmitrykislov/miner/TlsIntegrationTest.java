package io.dmitrykislov.miner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the app actually terminates TLS. Boots the whole context with
 * {@code server.ssl.enabled=true} and the committed self-signed <b>localhost</b> test cert
 * (src/test/resources/tls) on a random port, then makes a real <b>HTTPS</b> request with a
 * client that trusts that cert. If the TLS wiring (TLS_ENABLED / TLS_CERT / TLS_KEY → the
 * server SSL config) were broken, the handshake would fail and this test would error.
 * External devices are disabled so the context boots clean and fast.
 *
 * <p>Uses the JDK's {@link HttpClient} rather than reactor-netty's — the latter references
 * the HTTP/3 QUIC classes this build deliberately excludes to shrink the jar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.ssl.enabled=true",
        "server.ssl.certificate=classpath:tls/test-cert.pem",
        "server.ssl.certificate-private-key=classpath:tls/test-key.pem",
        "auth.enabled=false",                       // hit endpoints without a token
        "house.miner.enabled=false",
        "house.solar-analytics.enabled=false",
        "house.autopilot.enabled=false",
        "house.inverter.host=127.0.0.1",            // unreachable → poller degrades to offline
        "house.inverter.port=59999",
        "house.inverter.poll-interval-ms=3600000",
})
class TlsIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void servesOverHttpsWithTheConfiguredCertificate() throws Exception {
        HttpResponse<String> resp = trustAllHttpsClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/system")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"version\"");
    }

    /** A JDK HttpClient that trusts the self-signed test cert — we only need to reach TLS. */
    private static HttpClient trustAllHttpsClient() throws Exception {
        TrustManager[] trustAll = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder().sslContext(ssl).build();
    }
}
