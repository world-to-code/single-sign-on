package com.example.sso.federation.internal.application;

import com.example.sso.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two Redis-backed defences of the inbound SAML login, against real Redis — because both of them are
 * properties a mocked collaborator cannot hold. A mock returns whatever it was told, once or a hundred times;
 * swapping {@code getAndDelete} for {@code get}, or {@code setIfAbsent} for {@code set}, would leave every
 * unit test green while single-use correlation and replay defence became inert.
 *
 * <p>Where it matters these assert the PERSISTED state (the stored value, the TTL) rather than the return
 * value, since the return value is exactly the accessor that would mask a write-side defect.
 */
class SamlInboundStoreIT extends AbstractIntegrationTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String IDP = "https://idp.corp.example/entity";

    @Autowired
    SamlLoginCorrelationStore correlations;
    @Autowired
    SamlAssertionReplayGuard replayGuard;
    @Autowired
    StringRedisTemplate redis;

    private PendingSamlLogin pending() {
        return new PendingSamlLogin(UUID.randomUUID(), "corp", "_req-1",
                "https://acme.idp.example/saml2/sp/corp",
                "https://acme.idp.example/api/auth/federation/corp/acs", "handle-1");
    }

    // --- correlation: single use, and every component survives the round trip ------------------------

    @Test
    void aRecordedLoginRoundTripsEveryComponent() {
        // Six values are packed into one string; a mis-split silently swaps the tenant, the SP identity the
        // audience is compared against, or the handle that binds the browser.
        String relayState = UUID.randomUUID().toString();
        PendingSamlLogin recorded = pending();
        correlations.record(relayState, recorded);

        assertThat(correlations.consume(relayState)).contains(recorded);
    }

    @Test
    void aSecondConsumeOfTheSameRelayStateFindsNothing() {
        // THE single-use property. The RelayState is what binds an assertion to a login this product started,
        // so a second ACS post must not be able to spend the same record.
        String relayState = UUID.randomUUID().toString();
        correlations.record(relayState, pending());

        assertThat(correlations.consume(relayState)).isPresent();
        assertThat(correlations.consume(relayState)).isEmpty();
    }

    @Test
    void consumingDestroysTheRecordEvenThoughTheCallerOnlyReads() {
        String relayState = UUID.randomUUID().toString();
        correlations.record(relayState, pending());

        correlations.consume(relayState);

        assertThat(redis.hasKey("saml:sp:login:" + relayState)).isFalse();
    }

    @Test
    void aRecordedLoginExpiresRatherThanWaitingForever() {
        // An in-flight login left over from months ago must not stay consumable.
        String relayState = UUID.randomUUID().toString();
        correlations.record(relayState, pending());

        Long ttl = redis.getExpire("saml:sp:login:" + relayState, TimeUnit.SECONDS);
        assertThat(ttl).isPositive();
    }

    @Test
    void anAbsentOrMalformedRecordIsEmptyRatherThanAnError() {
        // The RelayState is attacker-supplied: a missing key, a blank value or a truncated record must all be a
        // clean refusal, not a 500 that distinguishes them.
        assertThat(correlations.consume(UUID.randomUUID().toString())).isEmpty();
        assertThat(correlations.consume(null)).isEmpty();
        assertThat(correlations.consume("  ")).isEmpty();

        String relayState = UUID.randomUUID().toString();
        redis.opsForValue().set("saml:sp:login:" + relayState, "only|two");
        assertThat(correlations.consume(relayState)).isEmpty();
    }

    // --- replay guard: first use only ----------------------------------------------------------------

    @Test
    void anAssertionIdIsSpendableExactlyOnce() {
        String assertionId = "_" + UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);

        assertThat(replayGuard.firstUse(ORG, IDP, assertionId, expiry)).isTrue();
        assertThat(replayGuard.firstUse(ORG, IDP, assertionId, expiry)).isFalse();
    }

    @Test
    void theSameAssertionIdFromAnotherUpstreamIsNotAlreadyBurnt() {
        // SAML only requires assertion ids to be unique PER ISSUER, and an upstream chooses its own — a global
        // namespace would let one tenant's IdP burn ids another's later reuses, refusing legitimate logins.
        String assertionId = "_" + UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(300);

        assertThat(replayGuard.firstUse(ORG, IDP, assertionId, expiry)).isTrue();
        assertThat(replayGuard.firstUse(ORG, "https://other-idp.example/entity", assertionId, expiry)).isTrue();
        assertThat(replayGuard.firstUse(UUID.randomUUID(), IDP, assertionId, expiry)).isTrue();
    }

    @Test
    void differentAssertionsDoNotBurnEachOther() {
        Instant expiry = Instant.now().plusSeconds(300);

        assertThat(replayGuard.firstUse(ORG, IDP, "_" + UUID.randomUUID(), expiry)).isTrue();
        assertThat(replayGuard.firstUse(ORG, IDP, "_" + UUID.randomUUID(), expiry)).isTrue();
    }

    @Test
    void theSpentRecordOutlivesTheAssertionItGuards() {
        // The record only has to last until the assertion expires — past that the validity window refuses it
        // anyway — so the TTL tracks the assertion, not a fixed guess.
        String assertionId = "_" + UUID.randomUUID();
        replayGuard.firstUse(ORG, IDP, assertionId, Instant.now().plusSeconds(600));

        Long ttl = redis.getExpire("saml:sp:assertion:" + ORG + ":" + IDP + ":" + assertionId, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(300L);
    }

    @Test
    void anAlreadyExpiredAssertionStillLeavesARecordLongEnoughToMatter() {
        // Without the floor the retention would be zero or negative for an assertion inside the clock-skew
        // window, and Redis would either reject it or drop the record immediately — no replay defence at all.
        String assertionId = "_" + UUID.randomUUID();

        assertThat(replayGuard.firstUse(ORG, IDP, assertionId, Instant.now().minusSeconds(30))).isTrue();

        Long ttl = redis.getExpire("saml:sp:assertion:" + ORG + ":" + IDP + ":" + assertionId, TimeUnit.SECONDS);
        assertThat(ttl).isPositive();
        assertThat(replayGuard.firstUse(ORG, IDP, assertionId, Instant.now().minusSeconds(30))).isFalse();
    }
}
