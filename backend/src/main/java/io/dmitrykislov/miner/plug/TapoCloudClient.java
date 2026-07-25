package io.dmitrykislov.miner.plug;

import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Controls the Tapo plug via the <b>TP-Link cloud</b>: log in with the account
 * credentials to get a token, find the device in the account's device list, and
 * relay SMART commands through the cloud {@code passthrough} method. Works even
 * when the local protocol (TPAP) is unsupported — provided the plug itself is
 * connected to TP-Link's cloud (otherwise the cloud returns "device offline").
 */
@Component
public class TapoCloudClient implements PlugTransport {

    private static final Logger log = LoggerFactory.getLogger(TapoCloudClient.class);

    /** Connection open timeout for the local/cloud HTTP calls. */
    private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(8);

    private final HouseProperties.Plug cfg;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpClient http;
    private final String terminalUuid = UUID.randomUUID().toString();

    private volatile String token;
    private volatile String appServerUrl;
    private volatile String deviceId;

    public TapoCloudClient(HouseProperties props) {
        this.cfg = props.plug();
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    // ---- PlugTransport -----------------------------------------------------

    @Override
    public synchronized JsonNode getDeviceInfo() throws Exception {
        return passthrough("{\"method\":\"get_device_info\"}");
    }

    @Override
    public synchronized JsonNode getEnergyUsage() throws Exception {
        return passthrough("{\"method\":\"get_energy_usage\"}");
    }

    @Override
    public synchronized void setOn(boolean on) throws Exception {
        passthrough("{\"method\":\"set_device_info\",\"params\":{\"device_on\":" + on + "}}");
    }

    // ---- cloud session -----------------------------------------------------

    private void login() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("appType", "Tapo_Android");
        params.put("cloudUserName", cfg.email());
        params.put("cloudPassword", cfg.password());
        params.put("terminalUUID", terminalUuid);
        JsonNode resp = cloudCall(cfg.cloudBaseUrl(), "login", params);
        int code = resp.path("error_code").asInt(-1);
        if (code != 0) {
            throw new AuthException("cloud login failed (error_code " + code + ") — check Tapo account credentials");
        }
        token = resp.path("result").path("token").asText(null);
        log.info("TP-Link cloud login ok");
    }

    private void ensureDevice() throws Exception {
        if (token == null) login();
        JsonNode resp = cloudCall(cfg.cloudBaseUrl() + "/?token=" + token, "getDeviceList", null);
        if (resp.path("error_code").asInt(-1) != 0) { // token likely stale → re-login once
            login();
            resp = cloudCall(cfg.cloudBaseUrl() + "/?token=" + token, "getDeviceList", null);
        }
        JsonNode list = resp.path("result").path("deviceList");
        String wantMac = cfg.normalisedMac();
        JsonNode found = null;
        for (JsonNode d : list) {
            String mac = d.path("deviceMac").asText("").replace("-", "").replace(":", "").toUpperCase();
            boolean isPlug = "SMART.TAPOPLUG".equals(d.path("deviceType").asText(""))
                    || d.path("deviceModel").asText("").contains("P110");
            if (!wantMac.isBlank() ? wantMac.equals(mac) : isPlug) { found = d; break; }
        }
        if (found == null) throw new IllegalStateException("plug not found in TP-Link account device list");
        deviceId = found.path("deviceId").asText();
        appServerUrl = found.path("appServerUrl").asText(cfg.cloudBaseUrl());
        if (found.path("status").asInt(0) != 1) {
            throw new IllegalStateException("plug is offline in TP-Link cloud (not reachable via cloud right now)");
        }
    }

    /** Relays a SMART command to the device through the cloud and returns its {@code result}. */
    private JsonNode passthrough(String requestData) throws Exception {
        if (deviceId == null || appServerUrl == null) ensureDevice();
        ObjectNode params = mapper.createObjectNode();
        params.put("deviceId", deviceId);
        params.put("requestData", requestData);

        JsonNode resp = cloudCall(appServerUrl + "/?token=" + token, "passthrough", params);
        int code = resp.path("error_code").asInt(-1);
        if (code == -20571) {
            throw new IllegalStateException("plug is offline in TP-Link cloud");
        }
        if (code == -20651 || code == -20675) { // token/session expired → re-establish and retry once
            token = null; deviceId = null; appServerUrl = null;
            ensureDevice();
            resp = cloudCall(appServerUrl + "/?token=" + token, "passthrough", params);
            code = resp.path("error_code").asInt(-1);
        }
        if (code != 0) throw new IllegalStateException("cloud passthrough error_code " + code);

        // result.responseData is a JSON string of the device's own {error_code,result}
        String responseData = resp.path("result").path("responseData").asText(null);
        JsonNode devResp = responseData != null ? mapper.readTree(responseData) : resp.path("result");
        int devCode = devResp.path("error_code").asInt(0);
        if (devCode != 0) throw new IllegalStateException("device error_code " + devCode);
        return devResp.path("result");
    }

    private JsonNode cloudCall(String url, String method, ObjectNode params) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("method", method);
        if (params != null) body.set("params", params);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    /** Decodes a base64 device alias/nickname (cloud returns names base64-encoded). */
    static String decodeBase64(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
