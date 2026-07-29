package io.dmitrykislov.miner.braiins;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative HTTP client for the Braiins OS+ GraphQL endpoint. A single
 * {@code POST /graphql} carries any query or mutation as a {@link GraphQlRequest};
 * the raw response body is returned for the caller to parse. Backed by a
 * {@code RestClient} proxy (see {@link BraiinsClientConfig}).
 *
 * <p>The body is returned as {@code byte[]} — not {@code JsonNode} — deliberately: Braiins OS+
 * answers some mutations (notably {@code stop}) with a JSON body mislabelled
 * {@code Content-Type: application/octet-stream}, for which there is no JSON message converter, so a
 * {@code JsonNode} return type throws "no suitable HttpMessageConverter" even though the command
 * succeeded. {@code byte[]} is converted for any content type, and {@link BraiinsMinerClient} parses
 * the bytes as JSON itself.
 */
@HttpExchange(accept = "application/json", contentType = "application/json")
public interface BraiinsApi {

    @PostExchange("/graphql")
    byte[] execute(@RequestBody GraphQlRequest request);
}
