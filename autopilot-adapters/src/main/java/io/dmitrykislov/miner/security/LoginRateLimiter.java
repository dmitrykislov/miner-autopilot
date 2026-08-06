package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.config.AuthProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small brute-force guard for the login endpoint: at most {@code auth.login-max-per-minute} login
 * <b>attempts</b> per client IP within a fixed one-minute window; beyond that the endpoint refuses
 * them (HTTP 429) until the window rolls. Attempts are counted <b>before</b> the password is checked,
 * which is what makes the limit hold under concurrency, and a successful login refunds the budget so
 * ordinary use does not accumulate against it. A user can still be throttled — five wrong passwords
 * in a minute is five, however they were spent.
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

    private record Attempt(long windowStart, int attempts) {}

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
        // Over the cap: sweep once, and if that didn't help, REFUSE rather than admit a new key.
        // This runs on a Netty event loop (there are two), so it must stay O(1) in the common case.
        // Sweeping inline on every request once the map was full turned an IP-rotation flood into an
        // O(n) scan per request on the very threads that serve the dashboard — the limiter became a
        // cheaper attack than the thing it defends. Refusing is also the safer answer: a caller we
        // have no room to track is exactly one we shouldn't be handing bcrypt time to.
        if (byIp.size() > MAX_TRACKED_IPS && !byIp.containsKey(ip)) {
            evictExpired(nowMs);
            if (byIp.size() > MAX_TRACKED_IPS) return false;
        }
        Attempt updated = byIp.compute(ip, (k, a) -> (a == null || nowMs - a.windowStart() >= WINDOW_MS)
                ? new Attempt(nowMs, 1)                            // new window
                : new Attempt(a.windowStart(), a.attempts() + 1));  // same window, one more attempt
        return updated.attempts() <= maxPerMinute;
    }

    /**
     * Periodic sweep so the map shrinks after a flood even if no further logins arrive, and so the
     * inline path above almost never has to scan.
     */
    @Scheduled(fixedDelay = WINDOW_MS)
    void sweep() {
        if (maxPerMinute > 0 && !byIp.isEmpty()) evictExpired(System.currentTimeMillis());
    }

    /**
     * Drop entries whose window has already rolled, to bound the map without amnesty.
     *
     * <p>This used to be {@code byIp.clear()}, which wiped <b>every</b> IP's budget — so an attacker who
     * sprayed enough distinct source addresses (trivial from a single IPv6 /64) reset their own lockout
     * and everyone else's. Evicting only expired windows keeps live counters intact. If every entry is
     * still live, tryAcquire refuses new keys rather than letting the map grow — see there.
     */
    private void evictExpired(long nowMs) {
        byIp.entrySet().removeIf(e -> nowMs - e.getValue().windowStart() >= WINDOW_MS);
    }

    /** A successful login returns the IP's budget, so ordinary use never accumulates against it. */
    public void recordSuccess(String ip) {
        byIp.remove(ip);
    }
}
