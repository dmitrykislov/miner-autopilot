package io.dmitrykislov.miner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-control configuration, bound from {@code auth.*}. A single shared password
 * (given as a <b>bcrypt hash</b>, never plaintext) gates every {@code /api/**} endpoint;
 * a successful login mints a signed bearer token the UI stores for {@link #tokenTtlDays} days.
 *
 * @param enabled            master switch; when false all endpoints are open (dev/tests only)
 * @param passwordHash       bcrypt hash of the UI password (e.g. {@code $2y$10$...}); blank ⇒
 *                           fail-closed (every request rejected) so a missing hash never
 *                           accidentally leaves the app unprotected
 * @param tokenTtlDays       how long an issued token stays valid (default 30 days)
 * @param loginMaxPerMinute  max FAILED logins per client IP per minute before the login endpoint
 *                           returns 429 (brute-force guard); default 5, {@code ≤ 0} disables it
 * @param trustForwardedFor  whether to read the client IP from the {@code X-Forwarded-For} header.
 *                           <b>Default false.</b> Enable it <b>only</b> behind a reverse proxy you
 *                           control that overwrites that header. When the app is reachable directly,
 *                           trusting it lets an attacker forge a fresh IP per request and escape the
 *                           rate limit entirely — and lock a real user out by forging theirs.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Boolean enabled, String passwordHash, int tokenTtlDays, int loginMaxPerMinute,
                             Boolean trustForwardedFor) {

    public AuthProperties {
        if (enabled == null) enabled = true;             // secure by default
        if (passwordHash == null) passwordHash = "";
        if (tokenTtlDays <= 0) tokenTtlDays = 30;
        if (trustForwardedFor == null) trustForwardedFor = false; // never trust a spoofable header by default
        // loginMaxPerMinute: the default (5) comes from application.yml; ≤ 0 here means "disabled".
    }

    /** True once a password hash has been configured. */
    public boolean configured() {
        return !passwordHash.isBlank();
    }
}
