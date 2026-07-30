package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.config.AuthProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static LoginRateLimiter limiter(int maxPerMinute) {
        return new LoginRateLimiter(new AuthProperties(true, "$2y$10$x", 30, maxPerMinute, false));
    }

    private static final String IP = "1.2.3.4";
    private static final long T0 = 1_000_000L;

    @Test void allowsExactlyTheConfiguredNumberOfAttemptsPerWindow() {
        var rl = limiter(3);
        assertThat(rl.tryAcquire(IP, T0)).isTrue();   // 1
        assertThat(rl.tryAcquire(IP, T0)).isTrue();   // 2
        assertThat(rl.tryAcquire(IP, T0)).isTrue();   // 3
        assertThat(rl.tryAcquire(IP, T0)).isFalse();  // 4th refused
        assertThat(rl.tryAcquire(IP, T0)).isFalse();  // stays refused
    }

    @Test void aSuccessfulLoginReturnsTheBudget() {
        var rl = limiter(3);
        rl.tryAcquire(IP, T0);
        rl.tryAcquire(IP, T0);
        rl.recordSuccess(IP);                          // a real user must not throttle themselves
        // full budget again
        assertThat(rl.tryAcquire(IP, T0)).isTrue();
        assertThat(rl.tryAcquire(IP, T0)).isTrue();
        assertThat(rl.tryAcquire(IP, T0)).isTrue();
        assertThat(rl.tryAcquire(IP, T0)).isFalse();
    }

    @Test void theWindowRollsAfterAMinuteAndTheBudgetIsSpendableAgain() {
        var rl = limiter(2);
        assertThat(rl.tryAcquire(IP, T0)).isTrue();
        assertThat(rl.tryAcquire(IP, T0)).isTrue();
        assertThat(rl.tryAcquire(IP, T0)).isFalse();               // blocked inside the window
        // A minute later the window rolls. Spend the WHOLE new budget to prove it genuinely reset
        // rather than merely permitting one more attempt.
        assertThat(rl.tryAcquire(IP, T0 + 60_000)).isTrue();
        assertThat(rl.tryAcquire(IP, T0 + 60_000)).isTrue();
        assertThat(rl.tryAcquire(IP, T0 + 60_000)).isFalse();
    }

    @Test void limitsPerIpIndependently() {
        var rl = limiter(1);
        assertThat(rl.tryAcquire("10.0.0.1", T0)).isTrue();
        assertThat(rl.tryAcquire("10.0.0.1", T0)).isFalse();  // this IP is spent
        assertThat(rl.tryAcquire("10.0.0.2", T0)).isTrue();   // a different IP is not
    }

    @Test void aNonPositiveLimitDisablesTheGuard() {
        var rl = limiter(0);
        for (int i = 0; i < 1000; i++) {
            assertThat(rl.tryAcquire(IP, T0)).isTrue();      // never blocks when disabled
        }
    }

    @Test void concurrentAttemptsCannotExceedTheLimit() throws Exception {
        // The reason tryAcquire counts and checks in one step. With a separate read-only check and a
        // post-hash increment, every request arriving during the ~0.5 s bcrypt window saw a stale
        // counter and was let through — so the limit was escapable just by issuing requests in
        // parallel. Here 200 threads race for a budget of 5; exactly 5 may win.
        int limit = 5, threads = 200;
        var rl = limiter(limit);
        var granted = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (rl.tryAcquire(IP, T0)) granted.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(granted.get())
                .as("concurrent attempts must not be able to pipeline past the limit")
                .isEqualTo(limit);
    }
}
