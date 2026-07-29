package io.dmitrykislov.miner.braiins;

import io.dmitrykislov.miner.config.HouseProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;

/** Builds the declarative {@link BraiinsApi} client from a timeout-bounded RestClient. */
@Configuration
public class BraiinsClientConfig {

    @Bean
    public BraiinsApi braiinsApi(HouseProperties props) {
        HouseProperties.Miner m = props.miner();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(m.requestTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(m.requestTimeoutMs()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(m.baseUrl())
                .requestFactory(factory);
        if (m.hasAuth()) {
            builder.defaultHeader("Authorization", "Bearer " + m.authToken());
        }
        var adapter = RestClientAdapter.create(builder.build());
        return HttpServiceProxyFactory.builderFor(adapter).build().createClient(BraiinsApi.class);
    }
}
