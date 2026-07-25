package io.dmitrykislov.miner.braiins;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Declarative HTTP client for the Braiins OS+ GraphQL endpoint. A single
 * {@code POST /graphql} carries any query or mutation as a {@link GraphQlRequest};
 * the raw response tree is returned for the caller to interpret. Backed by a
 * {@code RestClient} proxy (see {@link BraiinsClientConfig}).
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface BraiinsApi {

    @PostExchange("/graphql")
    JsonNode execute(@RequestBody GraphQlRequest request);
}
