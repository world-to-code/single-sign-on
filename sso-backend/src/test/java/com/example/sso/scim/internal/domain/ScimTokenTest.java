package com.example.sso.scim.internal.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link ScimToken#isActiveAt}: a token is active while enabled and unexpired. Pure
 * domain rule — asserted on the return value. (The disabled case has no constructor/mutator to reach
 * without a persistence round-trip, so it is left to the SCIM integration test.)
 */
class ScimTokenTest {

    @Test
    void aTokenWithNoExpiryIsAlwaysActive() {
        ScimToken token = new ScimToken("ci", "hash-1", null, null, null);

        assertThat(token.isActiveAt(Instant.now())).isTrue();
    }

    @Test
    void aTokenIsActiveBeforeItsExpiry() {
        Instant now = Instant.now();
        ScimToken token = new ScimToken("ci", "hash-2", now.plusSeconds(60), null, null);

        assertThat(token.isActiveAt(now)).isTrue();
    }

    @Test
    void aTokenIsInactiveAfterItsExpiry() {
        Instant now = Instant.now();
        ScimToken token = new ScimToken("ci", "hash-3", now.minusSeconds(60), null, null);

        assertThat(token.isActiveAt(now)).isFalse();
    }
}
