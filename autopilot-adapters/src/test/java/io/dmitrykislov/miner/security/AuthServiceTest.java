package io.dmitrykislov.miner.security;

import io.dmitrykislov.miner.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the password + token logic. The hash is generated at runtime for a
 * throwaway test password, so no real credential is committed.
 */
class AuthServiceTest {

    private static final String HASH = new BCryptPasswordEncoder().encode("test-pw");
    private final AuthService auth = new AuthService(new AuthProperties(true, HASH, 30, 5));

    // ---- password ----------------------------------------------------------
    @Test void verifiesCorrectPassword() {
        assertThat(auth.verifyPassword("test-pw")).isTrue();
    }

    @Test void rejectsWrongPassword() {
        assertThat(auth.verifyPassword("wrong")).isFalse();
    }

    @Test void rejectsNullOrEmptyPassword() {
        assertThat(auth.verifyPassword(null)).isFalse();
        assertThat(auth.verifyPassword("")).isFalse();
    }

    // ---- tokens -------------------------------------------------------------
    @Test void issuedTokenValidates() {
        assertThat(auth.isValidToken(auth.issueToken())).isTrue();
    }

    @Test void rejectsTamperedOrMalformedToken() throws Exception {
        String good = auth.issueToken();
        assertThat(auth.isValidToken(good + "x")).isFalse();      // mutated signature
        assertThat(auth.isValidToken("9999999999.bad")).isFalse(); // wrong signature
        assertThat(auth.isValidToken("garbage")).isFalse();        // no separator
        assertThat(auth.isValidToken("123.")).isFalse();           // empty signature (dot at end)
        assertThat(auth.isValidToken("")).isFalse();
        assertThat(auth.isValidToken(null)).isFalse();
        // Authentic signature but a non-numeric expiry → the parse must reject it, not throw.
        assertThat(auth.isValidToken(signPayload("notanumber"))).isFalse();
    }

    @Test void rejectsExpiredButAuthenticToken() throws Exception {
        // Forge an authentically-signed token with a past expiry — must still be rejected.
        assertThat(auth.isValidToken(signPayload(Long.toString(Instant.now().getEpochSecond() - 60)))).isFalse();
        assertThat(auth.isValidToken(signPayload(Long.toString(Instant.now().getEpochSecond() + 3600)))).isTrue();
    }

    @Test void tokenSurvivesAcrossServiceInstances() {
        // Same hash → same signing key → a token issued by one instance validates on another
        // (statelessness: a token survives a restart).
        String token = new AuthService(new AuthProperties(true, HASH, 30, 5)).issueToken();
        assertThat(new AuthService(new AuthProperties(true, HASH, 30, 5)).isValidToken(token)).isTrue();
    }

    // ---- fail-closed --------------------------------------------------------
    @Test void failsClosedWhenNoHashConfigured() {
        String realToken = auth.issueToken(); // valid under the configured service
        AuthService blank = new AuthService(new AuthProperties(true, "", 30, 5));
        assertThat(blank.verifyPassword("anything")).isFalse();
        // A blank-hash service accepts NO token — not a bogus one, and not even a token that
        // is genuinely valid under a configured service. Every request is rejected (fail-closed).
        assertThat(blank.isValidToken(realToken)).isFalse();
        assertThat(blank.isValidToken("9999999999.anything")).isFalse();
    }

    @Test void reportsEnabledFlag() {
        assertThat(new AuthService(new AuthProperties(true, HASH, 30, 5)).enabled()).isTrue();
        assertThat(new AuthService(new AuthProperties(false, HASH, 30, 5)).enabled()).isFalse();
    }

    /** Independently sign a payload with the same scheme, to forge tokens for the tests. */
    private static String signPayload(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HASH.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        return payload + "." + sig;
    }
}
