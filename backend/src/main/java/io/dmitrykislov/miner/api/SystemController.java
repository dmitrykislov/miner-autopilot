package io.dmitrykislov.miner.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * App/runtime info for the UI footer:
 * <ul>
 *   <li>{@code version}       — the application version, sourced from the Maven
 *       project version in pom.xml (e.g. "1.2.0")</li>
 *   <li>{@code startedAt}     — ISO-8601 instant the Spring context started</li>
 *   <li>{@code uptimeSeconds} — seconds since start (computed per request)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/system")
@CrossOrigin
public class SystemController {

    private final String version;
    private final long startupMillis;

    public SystemController(ApplicationContext ctx, @Value("${app.version:unknown}") String version) {
        this.version = version;
        this.startupMillis = ctx.getStartupDate(); // set at context refresh — stable for the run
    }

    @GetMapping
    public Map<String, Object> info() {
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startupMillis) / 1000);
        return Map.of(
                "version", version,
                "startedAt", Instant.ofEpochMilli(startupMillis).toString(),
                "uptimeSeconds", uptimeSeconds);
    }
}
