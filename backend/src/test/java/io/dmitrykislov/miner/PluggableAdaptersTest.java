package io.dmitrykislov.miner;

import io.dmitrykislov.miner.braiins.MinerService;
import io.dmitrykislov.miner.inverter.InverterPoller;
import io.dmitrykislov.miner.port.MinerDriver;
import io.dmitrykislov.miner.solaranalytics.SolarAnalyticsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the ports-and-adapters swap: with the built-in adapters turned off by config, none of them
 * is created, and a custom {@link MinerDriver} bean is wired in instead — so the app boots on
 * user-supplied sources/miner without any change to the engine or controllers. (A custom
 * SolarSource / ConsumptionSource would likewise be a bean feeding the always-present hubs; here we
 * assert the built-in opt-out and the miner-driver swap.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "house.inverter.enabled=false",          // no built-in Sungrow solar source
        "house.solar-analytics.enabled=false",   // no built-in consumption source
        "house.miner.driver=custom",             // no built-in Braiins driver
        "house.autopilot.enabled=false",
        "auth.enabled=false",
})
class PluggableAdaptersTest {

    @TestConfiguration
    static class CustomAdapters {
        @Bean
        MinerDriver customMinerDriver() {
            return mock(MinerDriver.class); // stand-in for a different miner's adapter
        }
    }

    @Autowired ApplicationContext ctx;
    @Autowired MinerDriver minerDriver;

    @Test
    void builtInAdaptersAreAbsentWhenTurnedOff() {
        assertThat(ctx.getBeanNamesForType(InverterPoller.class)).isEmpty();      // Sungrow solar off
        assertThat(ctx.getBeanNamesForType(SolarAnalyticsClient.class)).isEmpty(); // consumption off
        assertThat(ctx.getBeanNamesForType(MinerService.class)).isEmpty();         // Braiins driver off
    }

    @Test
    void theCustomMinerDriverIsWiredInPlaceOfBraiins() {
        assertThat(minerDriver).isNotInstanceOf(MinerService.class);
    }
}
