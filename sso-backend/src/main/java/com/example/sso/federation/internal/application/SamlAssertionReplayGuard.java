package com.example.sso.federation.internal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses an assertion this product has already consumed. The signature stays valid for the assertion's whole
 * validity window, so without a record of what was spent, anyone who observes one posted assertion — a proxy, a
 * browser extension, a shoulder-surfed URL — can post it again inside that window and sign in as its subject.
 *
 * <p>The record only has to outlive the assertion: once past {@code NotOnOrAfter} the validity check refuses it
 * anyway, so the TTL is that instant, not a fixed window.
 */
@Component
@RequiredArgsConstructor
class SamlAssertionReplayGuard {

    private static final String KEY_PREFIX = "saml:sp:assertion:";
    /** A floor for the TTL so an assertion that expires immediately still leaves a record long enough to matter. */
    private static final Duration MINIMUM_RETENTION = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final Clock clock;

    /** True the FIRST time this assertion id is seen FROM THIS UPSTREAM, false for every repeat. The key
     *  is qualified because an upstream chooses its own ids and SAML only requires them to be unique per
     *  issuer — a global namespace would let one tenant's IdP burn ids another's later reuses. */
    boolean firstUse(UUID orgId, String idpEntityId, String assertionId, Instant expiresAt) {
        Duration retention = Duration.between(clock.instant(), expiresAt);
        if (retention.compareTo(MINIMUM_RETENTION) < 0) {
            retention = MINIMUM_RETENTION;
        }
        String key = KEY_PREFIX + orgId + ":" + idpEntityId + ":" + assertionId;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", retention));
    }
}
