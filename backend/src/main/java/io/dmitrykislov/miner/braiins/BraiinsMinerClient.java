package io.dmitrykislov.miner.braiins;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Domain wrapper over the declarative {@link BraiinsApi}: exposes the four
 * operations we need against the Braiins OS+ GraphQL API and interprets GraphQL
 * / result-union errors into exceptions.
 */
@Component
public class BraiinsMinerClient {

    // Works whether the miner is running or stopped (no realtime "summary" here).
    private static final String STATUS_QUERY = """
        query Status {
          bosminer {
            info { modelName poolGroups { pools { url active } } }
            uptime { durationS since }
            config { ... on BosminerConfig { autotuning { enabled powerTarget } } }
          }
        }""";

    // Realtime stats + fans — only available while mining (errors "UNAVAILABLE" when stopped).
    private static final String REALTIME_QUERY = """
        query Realtime {
          bosminer { info {
            summary {
              realHashrate { mhs5S }
              power { approxConsumptionW limitW }
            }
            fans { name rpm speed }
          } }
        }""";

    private static final String START = """
        mutation WorkspaceBosStart {
          bosminer { start { ... on BosminerError { message __typename } __typename } __typename }
        }""";

    private static final String STOP = """
        mutation WorkspaceBosStop {
          bosminer { stop {
            ... on VoidResult { void __typename }
            ... on BosminerError { message __typename }
            __typename
          } __typename }
        }""";

    private static final String SET_POWER = """
        mutation SettingsPerformanceEditWithTuning($tuneInput: AutotuningIn!, $apply: Boolean!) {
          bosminer { config { updateAutotuning(input: $tuneInput, apply: $apply) {
            __typename
            ... on AttributeError { message __typename }
            ... on AutotuningError { mode message powerTarget hashrateTarget __typename }
          } __typename } __typename }
        }""";

    private final BraiinsApi api;

    public BraiinsMinerClient(BraiinsApi api) {
        this.api = api;
    }

    /** Core status (model, uptime, configured power target). */
    public JsonNode status() {
        return exec(GraphQlRequest.of("Status", STATUS_QUERY)).path("bosminer");
    }

    /** Realtime {@code info} node (summary + fans) — call only when the miner is running. */
    public JsonNode realtime() {
        return exec(GraphQlRequest.of("Realtime", REALTIME_QUERY))
                .path("bosminer").path("info");
    }

    /** Starts mining. */
    public void start() {
        JsonNode start = exec(GraphQlRequest.of("WorkspaceBosStart", START))
                .path("bosminer").path("start");
        failIfMessage(start, "start");
    }

    /** Stops mining. */
    public void stop() {
        JsonNode stop = exec(GraphQlRequest.of("WorkspaceBosStop", STOP))
                .path("bosminer").path("stop");
        failIfMessage(stop, "stop");
    }

    /**
     * Sets the autotuning power target (watts). {@code apply=false} previews;
     * {@code apply=true} commits the change.
     */
    public void setPowerTarget(int watts, boolean apply) {
        var vars = Map.<String, Object>of("apply", apply, "tuneInput", Map.of("powerTarget", watts));
        JsonNode res = exec(GraphQlRequest.of("SettingsPerformanceEditWithTuning", SET_POWER, vars))
                .path("bosminer").path("config").path("updateAutotuning");
        String type = res.path("__typename").asText("");
        if (type.endsWith("Error")) {
            throw new IllegalStateException("power update failed: " + res.path("message").asText(type));
        }
    }

    // ---- transport / error handling ----------------------------------------

    private JsonNode exec(GraphQlRequest req) {
        JsonNode resp = api.execute(req);
        JsonNode errors = resp.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("GraphQL error: " + errors.get(0).path("message").asText("unknown"));
        }
        return resp.path("data");
    }

    /** Result unions carry a {@code message} field only on the error variant. */
    private void failIfMessage(JsonNode node, String op) {
        if (node.hasNonNull("message")) {
            throw new IllegalStateException(op + " failed: " + node.path("message").asText());
        }
    }
}
