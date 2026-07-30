package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.config.AuthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static LoginRateLimiter limiter(int maxPerMinute) {
        return new LoginRateLimiter(new AuthProperties(true, "$2y$10$x", 30, maxPerMinute, false));
    }

    private static final String IP = "1.2.3.4";
    private static final long T0 = 1_000_000L;

    @Test void blocksAfterTheConfiguredNumberOfFailures() {
        var rl = limiter(3);
        for (int i = 0; i < 3; i++) {
            assertThat(rl.allowed(IP, T0)).isTrue();  // 3 failures allowed...
            rl.recordFailure(IP, T0);
        }
        assertThat(rl.allowed(IP, T0)).isFalse();     // ...the 4th is refused
    }

    @Test void aSuccessfulLoginResetsTheCounter() {
        var rl = limiter(3);
        rl.recordFailure(IP, T0);
        rl.recordFailure(IP, T0);
        rl.recordSuccess(IP);                          // reset
        // budget is full again → three more failures are allowed before blocking
        for (int i = 0; i < 3; i++) { assertThat(rl.allowed(IP, T0)).isTrue(); rl.recordFailure(IP, T0); }
        assertThat(rl.allowed(IP, T0)).isFalse();
    }

    @Test void theWindowRollsAfterAMinute() {
        var rl = limiter(2);
        rl.recordFailure(IP, T0);
        rl.recordFailure(IP, T0);
        assertThat(rl.allowed(IP, T0)).isFalse();          // blocked within the window
        assertThat(rl.allowed(IP, T0 + 60_000)).isTrue();  // a minute later → fresh window
    }

    @Test void limitsPerIpIndependently() {
        var rl = limiter(1);
        rl.recordFailure("10.0.0.1", T0);
        assertThat(rl.allowed("10.0.0.1", T0)).isFalse();  // this IP is blocked
        assertThat(rl.allowed("10.0.0.2", T0)).isTrue();   // a different IP is not
    }

    @Test void aNonPositiveLimitDisablesTheGuard() {
        var rl = limiter(0);
        for (int i = 0; i < 1000; i++) rl.recordFailure(IP, T0);
        assertThat(rl.allowed(IP, T0)).isTrue();           // never blocks when disabled
    }
}
