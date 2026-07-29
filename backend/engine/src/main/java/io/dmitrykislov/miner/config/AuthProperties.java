package io.dmitrykislov.miner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-control configuration, bound from {@code auth.*}. A single shared password
 * (given as a <b>bcrypt hash</b>, never plaintext) gates every {@code /api/**} endpoint;
 * a successful login mints a signed bearer token the UI stores for {@link #tokenTtlDays} days.
 *
 * @param enabled       master switch; when false all endpoints are open (dev/tests only)
 * @param passwordHash  bcrypt hash of the UI password (e.g. {@code $2y$10$...}); blank ⇒
 *                      fail-closed (every request rejected) so a missing hash never
 *                      accidentally leaves the app unprotected
 * @param tokenTtlDays  how long an issued token stays valid (default 30 days)
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(Boolean enabled, String passwordHash, int tokenTtlDays) {

    public AuthProperties {
        if (enabled == null) enabled = true;             // secure by default
        if (passwordHash == null) passwordHash = "";
        if (tokenTtlDays <= 0) tokenTtlDays = 30;
    }

    /** True once a password hash has been configured. */
    public boolean configured() {
        return !passwordHash.isBlank();
    }
}
