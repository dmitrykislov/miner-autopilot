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

    /** {@code attempts} counts login attempts in this window, not just failures — see tryAcquire. */
    private record Attempt(long windowStart, int failures) {}

    public LoginRateLimiter(AuthProperties cfg) {
        this.maxPerMinute = cfg.loginMaxPerMinute();
    }

    /**
     * Reserve one attempt for {@code ip}, returning false when its budget for this minute is spent.
     *
     * <p><b>Counts and checks in one atomic step, before the password is verified.</b> An earlier
     * version had a separate read-only {@code allowed()} check and only counted *failures* afterwards,
     * once bcrypt had finished. That is check-then-act: every request arriving during the ~0.3-0.8 s
     * bcrypt window saw a stale counter and was let through, so the real bound was "N per minute plus
     * everything that arrives while a guess is in flight" — escapable just by pipelining requests.
     * Counting attempts up front makes the limit hold under concurrency, which is what bounds how much
     * expensive hashing an unauthenticated caller can trigger.
     *
     * <p>A successful login gives the budget straight back ({@link #recordSuccess}), so a legitimate
     * user is never throttled by their own successful logins.
     */
    public boolean tryAcquire(String ip, long nowMs) {
        if (maxPerMinute <= 0) return true; // disabled
        if (byIp.size() > MAX_TRACKED_IPS) byIp.clear(); // guard against IP-rotation growth
        Attempt updated = byIp.compute(ip, (k, a) -> (a == null || nowMs - a.windowStart() >= WINDOW_MS)
                ? new Attempt(nowMs, 1)                            // new window
                : new Attempt(a.windowStart(), a.failures() + 1));  // same window, one more attempt
        return updated.failures() <= maxPerMinute;
    }

    /** A successful login clears the IP's failure budget. */
    public void recordSuccess(String ip) {
        byIp.remove(ip);
    }
}
