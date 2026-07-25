package io.dmitrykislov.miner.powersensor;

import io.dmitrykislov.miner.config.HouseProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Client for the Powersensor local UDP pub/sub API. Keeps a single socket open,
 * sends {@code subscribe(<lifetime>)} once, then continuously receives the
 * device's pushed {@code instant_power} datagrams (~1/s) — no polling. It
 * re-subscribes periodically so the stream never lapses.
 *
 * <p>The mains clamp reading (whole-home draw) feeds {@link HouseConsumptionState}
 * and is pushed to {@link HousePowerStreamService} the instant it arrives, so the
 * UI reflects consumption changes immediately over SSE.
 */
@Component
public class PowerSensorClient {

    private static final Logger log = LoggerFactory.getLogger(PowerSensorClient.class);

    /** microamps→watts scale factor for {@code unit == "u"} readings (per spec). */
    private static final double MICROAMP_TO_WATT = 19.3;

    private final HouseProperties.PowerSensor cfg;
    private final HouseConsumptionState consumption;
    private final HousePowerStreamService stream;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private volatile boolean running = false;
    private volatile DatagramSocket socket;
    private volatile Double lastMainsVoltage; // captured from the gateway plug readings
    private Thread thread;

    public PowerSensorClient(HouseProperties props, HouseConsumptionState consumption,
                             HousePowerStreamService stream) {
        this.cfg = props.powerSensor();
        this.consumption = consumption;
        this.stream = stream;
    }

    @PostConstruct
    public void start() {
        if (!cfg.enabled()) {
            log.info("Powersensor client disabled (house.power-sensor.enabled=false)");
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "powersensor-udp");
        thread.setDaemon(true);
        thread.start();
        log.info("Powersensor client started for {}:{}", cfg.host(), cfg.port());
    }

    @PreDestroy
    public void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        if (thread != null) thread.interrupt();
    }

    private void runLoop() {
        while (running) {
            try (DatagramSocket s = new DatagramSocket()) {
                this.socket = s;
                s.setSoTimeout(1000);
                InetAddress addr = InetAddress.getByName(cfg.host());
                subscribe(s, addr);
                long resubEveryMs = cfg.resubscribeIntervalSeconds() * 1000L;
                long nextResub = System.currentTimeMillis() + resubEveryMs;
                byte[] buf = new byte[65535];

                while (running) {
                    if (System.currentTimeMillis() >= nextResub) {
                        subscribe(s, addr);
                        nextResub = System.currentTimeMillis() + resubEveryMs;
                    }
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(pkt);
                    } catch (SocketTimeoutException e) {
                        continue; // no datagram this second; loop to check re-subscribe
                    }
                    handleDatagram(new String(pkt.getData(), pkt.getOffset(), pkt.getLength(),
                            StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Powersensor socket error: {} — retrying in 3s", e.toString());
                    sleep(3000);
                }
            }
        }
    }

    private void subscribe(DatagramSocket s, InetAddress addr) throws Exception {
        byte[] msg = ("subscribe(" + cfg.subscribeLifetimeSeconds() + ")\n")
                .getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(msg, msg.length, addr, cfg.port()));
    }

    /** A datagram may hold one or more newline-delimited JSON objects. */
    private void handleDatagram(String payload) {
        for (String line : payload.split("\n")) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = mapper.readTree(line);
            } catch (Exception e) {
                log.trace("ignoring non-JSON datagram");
                continue;
            }
            Parsed p = parse(msg, cfg);
            if (p == null) continue;
            if (p.kind == Kind.GATEWAY) {
                lastMainsVoltage = p.voltage; // keep the mains voltage reference
            } else if (p.kind == Kind.CLAMP) {
                HousePower reading = HousePower.measured(p.watts, lastMainsVoltage, p.mac, Instant.now());
                consumption.update(reading);
                stream.publish(reading); // instant push to the UI
                log.debug("House power {} W (clamp {})", reading.powerW(), p.mac);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---- pure parsing (unit-tested) ---------------------------------------

    enum Kind { CLAMP, GATEWAY }

    record Parsed(Kind kind, double watts, Double voltage, String mac) {}

    /**
     * Interprets one {@code instant_power} message. Returns null for other/invalid
     * messages. Converts raw-current ({@code unit=="u"}) readings to watts and
     * classifies the source as the whole-home clamp or the gateway plug.
     */
    static Parsed parse(JsonNode msg, HouseProperties.PowerSensor cfg) {
        if (msg == null || !"instant_power".equals(msg.path("type").asText(null))) return null;
        if (!msg.hasNonNull("power")) return null;
        double power = msg.path("power").asDouble();
        String unit = msg.path("unit").asText("w").toLowerCase();
        double watts = "u".equals(unit) ? power / MICROAMP_TO_WATT : power;
        Double voltage = msg.hasNonNull("voltage") ? msg.path("voltage").asDouble() : null;
        String mac = msg.path("mac").asText(null);
        Kind kind = cfg.isClamp(mac, voltage) ? Kind.CLAMP : Kind.GATEWAY;
        return new Parsed(kind, watts, voltage, mac);
    }
}
