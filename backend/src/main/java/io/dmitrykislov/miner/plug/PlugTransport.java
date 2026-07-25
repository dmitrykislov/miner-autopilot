package io.dmitrykislov.miner.plug;

import tools.jackson.databind.JsonNode;

/**
 * Abstraction over how we reach the Tapo plug: {@link TapoKlapClient} (local
 * KLAP over the LAN) or {@link TapoCloudClient} (TP-Link cloud relay). Both
 * return the device's native SMART-protocol {@code result} payloads, so
 * {@link PlugService} parses them the same way regardless of transport.
 */
public interface PlugTransport {

    /** {@code get_device_info} result (device_on, nickname, model, ...). */
    JsonNode getDeviceInfo() throws Exception;

    /** {@code get_energy_usage} result (current_power mW, today_energy Wh); may be unsupported. */
    JsonNode getEnergyUsage() throws Exception;

    /** Switches the relay on/off. */
    void setOn(boolean on) throws Exception;

    /** Thrown when authentication fails (bad account credentials). */
    class AuthException extends RuntimeException {
        public AuthException(String m) { super(m); }
    }
}
