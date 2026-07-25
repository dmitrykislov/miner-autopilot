package io.dmitrykislov.miner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.dmitrykislov.miner.config.HouseProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HouseProperties.class)
public class MinerControllerApplication {
    static {
        // Must be set before ANY java.net.http.HttpClient initialises its TLS layer
        // (WiNet-S / Tapo clients all use it). The WiNet-S dongle's self-signed cert
        // has no SAN, so hostname verification must be disabled for these LAN devices.
        // Setting it here (class-load of the main class) guarantees it wins the race
        // regardless of Spring bean-creation order.
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    public static void main(String[] args) {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        SpringApplication.run(MinerControllerApplication.class, args);
    }
}
