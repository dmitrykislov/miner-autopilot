package io.dmitrykislov.miner.plug;

import io.dmitrykislov.miner.config.HouseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Selects the active plug transport based on {@code house.plug.mode}. */
@Configuration
public class PlugConfig {

    @Bean
    @Primary
    public PlugTransport plugTransport(HouseProperties props, TapoKlapClient klap, TapoCloudClient cloud) {
        return props.plug().isCloud() ? cloud : klap;
    }
}
