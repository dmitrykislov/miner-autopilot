package io.dmitrykislov.miner.plug;

import io.dmitrykislov.miner.config.HouseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;

/**
 * Local client for the TP-Link Tapo P110 using the <b>KLAP</b> protocol
 * (encrypted HTTP on port 80). There is no device-local password — the plug is
 * authenticated with the TP-Link account credentials, hashed locally.
 *
 * <p>Flow: {@code handshake1} (seed exchange + credential proof) →
 * {@code handshake2} → an AES-CBC session keyed from both seeds and the auth
 * hash. Each subsequent {@code /request?seq=N} carries a signed, encrypted JSON
 * command. The exact hash variant (KLAP v1 vs v2) is auto-detected by matching
 * the server hash returned in handshake1.
 */
@Component
public class TapoKlapClient implements PlugTransport {

    private static final Logger log = LoggerFactory.getLogger(TapoKlapClient.class);

    /** Connection open timeout for the local/cloud HTTP calls. */
    private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(6);
    private static final SecureRandom RNG = new SecureRandom();

    private final HouseProperties.Plug cfg;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final HttpClient http;

    // session state (guarded by `this`)
    private byte[] key, iv12, sig, authHash, localSeed, remoteSeed;
    private int seq;
    private String cookie;
    private boolean authenticated;

    public TapoKlapClient(HouseProperties props) {
        this.cfg = props.plug();
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    // ---- public API --------------------------------------------------------

    @Override
    public synchronized JsonNode getDeviceInfo() throws Exception {
        return request("get_device_info", null);
    }

    @Override
    public synchronized JsonNode getEnergyUsage() throws Exception {
        return request("get_energy_usage", null);
    }

    /** Switches the relay on/off. */
    @Override
    public synchronized void setOn(boolean on) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("device_on", on);
        request("set_device_info", params);
    }

    // ---- KLAP handshake ----------------------------------------------------

    private void handshake() throws Exception {
        authenticated = false;
        localSeed = new byte[16];
        RNG.nextBytes(localSeed);

        HttpResponse<byte[]> r1 = post("/handshake1", localSeed, null);
        if (r1.statusCode() != 200) {
            throw new IllegalStateException("handshake1 HTTP " + r1.statusCode()
                    + " (plug locked/local access off — power-cycle it)");
        }
        byte[] body = r1.body();
        remoteSeed = Arrays.copyOfRange(body, 0, 16);
        byte[] serverHash = Arrays.copyOfRange(body, 16, 48);
        cookie = extractCookie(r1);

        // Detect KLAP v2 (sha256/sha1) vs v1 (md5/md5) by matching the server hash.
        byte[] ahV2 = sha256(concat(sha1(bytes(cfg.email())), sha1(bytes(cfg.password()))));
        byte[] ahV1 = md5(concat(md5(bytes(cfg.email())), md5(bytes(cfg.password()))));
        boolean v2;
        if (Arrays.equals(serverHash, sha256(concat(localSeed, remoteSeed, ahV2)))) {
            authHash = ahV2; v2 = true;
        } else if (Arrays.equals(serverHash, sha256(concat(localSeed, ahV1)))) {
            authHash = ahV1; v2 = false;
        } else {
            throw new AuthException("handshake1 server hash mismatch — check Tapo account email/password");
        }

        byte[] h2 = v2 ? sha256(concat(remoteSeed, localSeed, authHash))
                       : sha256(concat(remoteSeed, authHash));
        HttpResponse<byte[]> r2 = post("/handshake2", h2, cookie);
        if (r2.statusCode() != 200) {
            throw new IllegalStateException("handshake2 HTTP " + r2.statusCode());
        }

        key = sha256(concat(bytes("lsk"), localSeed, remoteSeed, authHash), 16);
        byte[] ivFull = sha256(concat(bytes("iv"), localSeed, remoteSeed, authHash));
        iv12 = Arrays.copyOfRange(ivFull, 0, 12);
        seq = ByteBuffer.wrap(ivFull, ivFull.length - 4, 4).getInt();
        sig = sha256(concat(bytes("ldk"), localSeed, remoteSeed, authHash), 28);
        authenticated = true;
        log.info("Tapo plug KLAP session established (variant {})", v2 ? "v2" : "v1");
    }

    // ---- encrypted request -------------------------------------------------

    private JsonNode request(String method, ObjectNode params) throws Exception {
        if (!authenticated) handshake();
        try {
            return doRequest(method, params);
        } catch (RetryableException e) {
            log.info("Tapo session stale ({}), re-handshaking", e.getMessage());
            handshake();
            return doRequest(method, params);
        }
    }

    private JsonNode doRequest(String method, ObjectNode params) throws Exception {
        ObjectNode inner = mapper.createObjectNode();
        inner.put("method", method);
        if (params != null) inner.set("params", params);

        seq += 1;
        byte[] plain = mapper.writeValueAsBytes(inner);
        byte[] payload = encrypt(plain, seq);
        HttpResponse<byte[]> resp = post("/request?seq=" + seq, payload, cookie);
        if (resp.statusCode() == 403) {
            throw new RetryableException("HTTP 403");
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("request HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(decrypt(resp.body(), seq));
        int code = root.path("error_code").asInt(0);
        if (code == 9999) throw new RetryableException("error_code 9999 (session timeout)");
        if (code != 0) throw new IllegalStateException("plug error_code " + code);
        return root.path("result");
    }

    private static class RetryableException extends RuntimeException {
        RetryableException(String m) { super(m); }
    }

    // ---- crypto ------------------------------------------------------------

    private byte[] encrypt(byte[] plain, int seq) throws Exception {
        byte[] iv = concat(iv12, intBytes(seq));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] ct = c.doFinal(plain);
        byte[] signature = sha256(concat(sig, intBytes(seq), ct));
        return concat(signature, ct);
    }

    private byte[] decrypt(byte[] payload, int seq) throws Exception {
        byte[] iv = concat(iv12, intBytes(seq));
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(Arrays.copyOfRange(payload, 32, payload.length)); // skip 32-byte signature
    }

    // ---- transport ---------------------------------------------------------

    private HttpResponse<byte[]> post(String path, byte[] body, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl() + path))
                .timeout(Duration.ofMillis(cfg.requestTimeoutMs()))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (cookie != null) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String extractCookie(HttpResponse<?> r) {
        return r.headers().firstValue("Set-Cookie")
                .map(sc -> sc.split(";", 2)[0]) // keep just TP_SESSIONID=...
                .orElse(null);
    }

    // ---- hashing / byte utils ----------------------------------------------

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] intBytes(int v) { return ByteBuffer.allocate(4).putInt(v).array(); }

    private static byte[] digest(String algo, byte[] in) throws Exception {
        return MessageDigest.getInstance(algo).digest(in);
    }
    private static byte[] sha1(byte[] in) { return quiet("SHA-1", in); }
    private static byte[] md5(byte[] in) { return quiet("MD5", in); }
    private static byte[] sha256(byte[] in) { return quiet("SHA-256", in); }
    private static byte[] sha256(byte[] in, int len) { return Arrays.copyOfRange(quiet("SHA-256", in), 0, len); }
    private static byte[] quiet(String algo, byte[] in) {
        try { return digest(algo, in); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n]; int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }
}
