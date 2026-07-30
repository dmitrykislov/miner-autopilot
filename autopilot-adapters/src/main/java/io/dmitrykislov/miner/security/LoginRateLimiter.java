package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.config.AuthProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small brute-force guard for the login endpoint: at most {@code auth.login-max-per-minute}
 * <b>failed</b> logins per client IP within a fixed one-minute window; beyond that the endpoint
 * refuses attempts (HTTP 429) until the window rolls. Only failures count — a successful login
 * resets that IP — so normal users are never throttled, but guessing is capped and expensive
 * bcrypt work is skipped once the limit is hit.
 *
 * <p>State is a tiny in-memory map (single instance / one Pi). A crude size cap prevents unbounded
 * growth if an attacker rotates IPs. A limit of {@code ≤ 0} disables the guard entirely.
 */
@Component
public class LoginRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_IPS = 10_000;

    private final int maxPerMinute;
    private final Map<String, Attempt> byIp = new ConcurrentHashMap<>();

    private record Attempt(long windowStart, int failures) {}

    public LoginRateLimiter(AuthProperties cfg) {
        this.maxPerMinute = cfg.loginMaxPerMinute();
    }

    /** True if another login attempt from {@code ip} is allowed right now. */
    public boolean allowed(String ip, long nowMs) {
        if (maxPerMinute <= 0) return true; // disabled
        Attempt a = byIp.get(ip);
        return a == null || nowMs - a.windowStart() >= WINDOW_MS || a.failures() < maxPerMinute;
    }

    /** Record a failed login for {@code ip} (starts or extends its current window). */
    public void recordFailure(String ip, long nowMs) {
        if (maxPerMinute <= 0) return;
        if (byIp.size() > MAX_TRACKED_IPS) byIp.clear(); // guard against IP-rotation growth
        byIp.compute(ip, (k, a) -> (a == null || nowMs - a.windowStart() >= WINDOW_MS)
                ? new Attempt(nowMs, 1)
                : new Attempt(a.windowStart(), a.failures() + 1));
    }

    /** A successful login clears the IP's failure budget. */
    public void recordSuccess(String ip) {
        byIp.remove(ip);
    }
}
