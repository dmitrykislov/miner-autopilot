package io.dmitrykislov.miner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.dmitrykislov.miner.config.AuthProperties;
import io.dmitrykislov.miner.config.HouseProperties;
import io.dmitrykislov.miner.history.HistoryProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({HouseProperties.class, AuthProperties.class, HistoryProperties.class})
public class MinerAutopilotApplication {
    static {
        // Must be set before ANY java.net.http.HttpClient initialises its TLS layer.
        // The WiNet-S dongle's self-signed cert has no SAN, so hostname verification
        // must be disabled for these LAN devices.
        // Setting it here (class-load of the main class) guarantees it wins the race
        // regardless of Spring bean-creation order.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    public static void main(String[] args) {
        // The TLS property is set in the static initialiser above (runs at class-load,
        // before main), so it is deliberately not repeated here.
        SpringApplication.run(MinerAutopilotApplication.class, args);
    }
}
