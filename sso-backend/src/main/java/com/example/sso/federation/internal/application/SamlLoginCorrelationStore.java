package com.example.sso.federation.internal.application;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The in-flight SAML logins, keyed by the RelayState the upstream echoes back. Redis rather than the HTTP
 * session because the ACS POST arrives cross-site without the cookie, and shared rather than per-node because
 * the browser may land on a different instance than the one that started the login.
 *
 * <p>Consumption is SINGLE-USE and atomic ({@code GETDEL}): the RelayState is the only thing binding an assertion
 * to a request this product made, so letting two ACS posts consume one record would re-open the replay the
 * {@code InResponseTo} check exists to close.
 */
@Component
@RequiredArgsConstructor
class SamlLoginCorrelationStore {

    private static final String KEY_PREFIX = "saml:sp:login:";
    /** A UUID, a lowercase-alnum-hyphen alias, an underscore-prefixed UUID and two absolute https URLs — none
     *  of which can contain the separator, plus a base64url handle. */
    private static final String SEPARATOR = "|";

    private final StringRedisTemplate redis;

    @Value("${sso.saml.inbound.request-ttl}")
    private Duration requestTtl;

    void record(String relayState, PendingSamlLogin pending) {
        redis.opsForValue().set(key(relayState),
                String.join(SEPARATOR, pending.orgId().toString(), pending.alias(), pending.requestId(),
                        pending.spEntityId(), pending.acsUrl(), pending.browserHandle()),
                requestTtl);
    }

    /** Reads and destroys the record in one operation — a second ACS post for the same RelayState finds nothing. */
    Optional<PendingSamlLogin> consume(String relayState) {
        if (relayState == null || relayState.isBlank()) {
            return Optional.empty();
        }
        String stored = redis.opsForValue().getAndDelete(key(relayState));
        if (stored == null) {
            return Optional.empty();
        }
        String[] parts = stored.split("\\" + SEPARATOR, 6);
        if (parts.length != 6) {
            return Optional.empty();
        }
        return Optional.of(new PendingSamlLogin(UUID.fromString(parts[0]), parts[1], parts[2], parts[3],
                parts[4], parts[5]));
    }

    private String key(String relayState) {
        return KEY_PREFIX + relayState;
    }
}
