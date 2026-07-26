package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.autopilot.AutopilotStatus;
import io.dmitrykislov.miner.autopilot.AutopilotStreamService;
import io.dmitrykislov.miner.autopilot.MinerAutopilot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Enable/disable the solar-margin autopilot and observe its status:
 * <ul>
 *   <li>{@code GET  /api/autopilot} — current {@link AutopilotStatus} (one-shot)</li>
 *   <li>{@code GET  /api/autopilot/stream} — live SSE feed of status changes</li>
 *   <li>{@code POST /api/autopilot/enable} · {@code /disable} — toggle, returns new status</li>
 * </ul>
 * Like every {@code /api/**} endpoint these require a valid auth token.
 */
@RestController
@RequestMapping("/api/autopilot")
@CrossOrigin
public class AutopilotController {

    private final MinerAutopilot autopilot;
    private final AutopilotStreamService stream;

    public AutopilotController(MinerAutopilot autopilot, AutopilotStreamService stream) {
        this.autopilot = autopilot;
        this.stream = stream;
    }

    @GetMapping({"", "/status"})
    public AutopilotStatus status() {
        return autopilot.status();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AutopilotStatus> stream() {
        return Sse.withHeartbeat(stream.stream(), stream::latest);
    }

    @PostMapping("/enable")
    public AutopilotStatus enable() {
        autopilot.setEnabled(true);
        return autopilot.status();
    }

    @PostMapping("/disable")
    public AutopilotStatus disable() {
        autopilot.setEnabled(false);
        return autopilot.status();
    }
}
