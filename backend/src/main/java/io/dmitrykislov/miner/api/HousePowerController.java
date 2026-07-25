package io.dmitrykislov.miner.api;

import io.dmitrykislov.miner.powersensor.HouseConsumptionState;
import io.dmitrykislov.miner.powersensor.HousePower;
import io.dmitrykislov.miner.powersensor.HousePowerStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


/**
 * Live measured whole-home power (from the Powersensor clamp):
 * <ul>
 *   <li>{@code GET /api/house/stream} — SSE feed pushed the instant each reading arrives</li>
 *   <li>{@code GET /api/house/latest} — the most recent reading (one-shot)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/house")
@CrossOrigin
public class HousePowerController {

    private final HousePowerStreamService stream;
    private final HouseConsumptionState consumption;

    public HousePowerController(HousePowerStreamService stream, HouseConsumptionState consumption) {
        this.stream = stream;
        this.consumption = consumption;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<HousePower> stream() {
        return Sse.withHeartbeat(stream.stream(), stream::latest);
    }

    @GetMapping("/latest")
    public HousePower latest() {
        HousePower l = stream.latest();
        return l != null ? l : consumption.latest();
    }
}
