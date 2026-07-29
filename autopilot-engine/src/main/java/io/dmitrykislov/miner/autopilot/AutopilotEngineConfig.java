package io.dmitrykislov.miner.autopilot;

import io.dmitrykislov.miner.config.HouseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the pure autopilot engine into Spring. {@link EnergyAverages} is a shared,
 * long-lived bean: the {@link EnergySampler} feeds it raw solar/consumption samples and
 * {@link MinerAutopilot} reads the averaged surplus signals from it each tick.
 * {@link AutopilotGovernor} is built inside {@link MinerAutopilot} (it needs the miner's
 * power ceiling), so only {@code EnergyAverages} is a bean here.
 */
@Configuration
public class AutopilotEngineConfig {

    @Bean
    public EnergyAverages energyAverages(HouseProperties props) {
        HouseProperties.Autopilot cfg = props.autopilot();
        return new EnergyAverages(
                Duration.ofMillis(cfg.shortWindowMs()),
                Duration.ofMillis(cfg.longWindowMs()),
                Duration.ofMillis(cfg.freshWithinMs()),
                Duration.ofMillis(cfg.shortCoverageMs()),
                Duration.ofMillis(cfg.longCoverageMs()));
    }
}
