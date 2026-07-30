package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Single-password access control. The password is stored only as a bcrypt hash
 * ({@code auth.password-hash}); a correct login mints a stateless, HMAC-signed
 * bearer token carrying its own expiry, which every subsequent request must present.
 *
 * <p>The token is {@code <expiryEpochSec>.<base64url(HMAC-SHA256(expiry, key))>}. The
 * signing key is derived from the bcrypt hash, so it is stable across restarts (a token
 * survives a reboot) yet secret. Validation recomputes the HMAC (constant-time compare)
 * and checks the embedded expiry — no server-side session state, so no store to lose.
 *
 * <p><b>Fail-closed:</b> if auth is enabled but no hash is configured, no password ever
 * matches and no token ever validates, so every request is rejected rather than the app
 * silently running unprotected.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties cfg;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final byte[] signingKey;

    public AuthService(AuthProperties cfg) {
        this.cfg = cfg;
        this.signingKey = cfg.passwordHash().getBytes(StandardCharsets.UTF_8);
        if (cfg.enabled() && !cfg.configured()) {
            log.warn("auth.enabled=true but auth.password-hash is blank — ALL requests will be "
                    + "rejected. Set AUTH_PASSWORD_HASH (bcrypt) to allow logins.");
        }
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    /** True if the raw password matches the configured bcrypt hash. */
    public boolean verifyPassword(String raw) {
        if (!cfg.configured() || raw == null || raw.isEmpty()) return false;
        try {
            return bcrypt.matches(raw, cfg.passwordHash());
        } catch (Exception e) {
            log.warn("bcrypt verification failed (malformed hash?): {}", e.toString());
            return false;
        }
    }

    /** Mint a signed token valid for {@code token-ttl-days}. */
    public String issueToken() {
        long exp = Instant.now().getEpochSecond() + (long) cfg.tokenTtlDays() * 86_400L;
        String payload = Long.toString(exp);
        return payload + "." + sign(payload);
    }

    /** Seconds an SSE ticket stays valid — long enough to open a stream, short enough that a leak is harmless. */
    static final long SSE_TICKET_TTL_SECONDS = 60L;
    private static final String SSE_PREFIX = "sse.";

    /**
     * Mint a short-lived, SSE-only ticket. {@code EventSource} can't send an {@code Authorization}
     * header, so streams carry their credential in the URL ({@code ?token=…}) — where it can leak
     * into proxy/access logs, history and {@code Referer}. This ticket bounds that risk: it lives
     * ~60 s and (see {@link AuthWebFilter}) is accepted only on the stream endpoints, never to
     * mutate anything. Namespaced ("sse.") so it can't be used as a full API token, and vice versa.
     * Stateless like the main token — signed, not stored — so nothing grows and it survives restarts.
     */
    public String issueSseTicket() {
        long exp = Instant.now().getEpochSecond() + SSE_TICKET_TTL_SECONDS;
        String payload = SSE_PREFIX + exp;
        return payload + "." + sign(payload);
    }

    /** True if the ticket is an authentic, unexpired SSE ticket (and not a full token). */
    public boolean isValidSseTicket(String ticket) {
        if (!cfg.configured() || ticket == null || !ticket.startsWith(SSE_PREFIX)) return false;
        int lastDot = ticket.lastIndexOf('.');
        if (lastDot <= SSE_PREFIX.length() - 1) return false; // need a payload after "sse." and before the sig
        String payload = ticket.substring(0, lastDot);
        String sig = ticket.substring(lastDot + 1);
        if (sig.isEmpty() || !constantTimeEquals(sig, sign(payload))) return false;
        try {
            return Long.parseLong(payload.substring(SSE_PREFIX.length())) > Instant.now().getEpochSecond();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True if the token is authentic (untampered) and not expired. */
    public boolean isValidToken(String token) {
        if (!cfg.configured() || token == null) return false;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return false;
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(sig, sign(payload))) return false;
        try {
            return Long.parseLong(payload) > Instant.now().getEpochSecond();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
