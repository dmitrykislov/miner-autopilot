package io.dmitrykislov.miner.inverter.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/**
 * The common envelope every WiNet-S WebSocket response is wrapped in.
 *
 * <p>Known {@link #resultCode} values observed from this dongle:
 * <ul>
 *   <li><b>1</b>   — success; {@link #resultData} holds the service payload</li>
 *   <li><b>200</b> — bare acknowledgement (no data), e.g. {@code device/getType}</li>
 *   <li><b>106</b> — session expired / token stale ("ACCOUNT_OUT_FRESH"); re-login</li>
 *   <li><b>211</b> — feature not applicable, e.g. {@code real_battery} on a batteryless unit</li>
 *   <li><b>215</b> — internal/abnormal, e.g. {@code meter}/{@code energyflow} with no meter fitted</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WiNetEnvelope(
        // numeric status of the call (see table above)
        @JsonProperty("result_code") int resultCode,
        // human/i18n status message ("success", "I18N_COMMON_ACCOUNT_OUT_FRESH", ...)
        @JsonProperty("result_msg") String resultMsg,
        // service-specific payload; shape depends on the requested service
        @JsonProperty("result_data") JsonNode resultData) {

    public static final int SUCCESS = 1;
    public static final int SESSION_EXPIRED = 106;

    public boolean isSuccess() {
        return resultCode == SUCCESS;
    }
}
