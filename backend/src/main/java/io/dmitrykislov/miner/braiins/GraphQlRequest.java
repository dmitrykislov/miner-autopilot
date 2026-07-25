package io.dmitrykislov.miner.braiins;

import java.util.Map;

/**
 * A GraphQL request body: {@code {operationName, query, variables}} — the exact
 * shape the Braiins web UI POSTs to {@code /graphql}.
 */
public record GraphQlRequest(String operationName, String query, Map<String, Object> variables) {

    public static GraphQlRequest of(String operationName, String query) {
        return new GraphQlRequest(operationName, query, Map.of());
    }

    public static GraphQlRequest of(String operationName, String query, Map<String, Object> variables) {
        return new GraphQlRequest(operationName, query, variables);
    }
}
