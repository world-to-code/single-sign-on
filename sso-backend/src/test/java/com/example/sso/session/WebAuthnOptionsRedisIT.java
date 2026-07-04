package com.example.sso.session;

import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Guards the passkey ceremonies against the Redis session store. {@code PublicKeyCredentialCreationOptions}
 * is NOT Serializable, so registration must persist only the challenge and rebuild the options (see
 * {@code Fido2FactorHandler} / {@code RegistrationChallengeOptionsRepository}); the login
 * {@code PublicKeyCredentialRequestOptions} IS Serializable and is kept whole. This verifies both the
 * raw object still fails (so the fix stays necessary) and that the serializable forms round-trip.
 */
class WebAuthnOptionsRedisIT extends AbstractIntegrationTest {

    static final String ATTR = "probe";

    @Autowired
    WebAuthnRelyingPartyOperations operations;
    @Autowired
    RedisIndexedSessionRepository sessions;

    private final UsernamePasswordAuthenticationToken auth =
            UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of());

    @Test
    void rawCreationOptionsCannotBeStored_soTheChallengeIsPersistedAndTheOptionsRebuilt() {
        PublicKeyCredentialCreationOptions options = operations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(auth));

        // The raw object is not Serializable — storing it in a Redis session must fail (why the fix exists).
        RedisSession bad = sessions.createSession();
        bad.setAttribute(ATTR, options);
        assertThat(catchThrowable(() -> sessions.save(bad)))
                .as("raw CreationOptions is not Redis-serializable").isNotNull();

        // The fix: persist only the challenge (Serializable) and round-trip it.
        RedisSession good = sessions.createSession();
        good.setAttribute(ATTR, options.getChallenge());
        sessions.save(good);
        Bytes challenge = sessions.findById(good.getId()).getAttribute(ATTR);
        assertThat(challenge).isEqualTo(options.getChallenge());
        sessions.deleteById(good.getId());

        // Rebuilding from a fresh derivation + the stored challenge yields the original challenge.
        PublicKeyCredentialCreationOptions fresh = operations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(auth));
        PublicKeyCredentialCreationOptions rebuilt = PublicKeyCredentialCreationOptions.builder()
                .rp(fresh.getRp()).user(fresh.getUser()).challenge(challenge)
                .pubKeyCredParams(fresh.getPubKeyCredParams()).timeout(fresh.getTimeout())
                .excludeCredentials(fresh.getExcludeCredentials())
                .authenticatorSelection(fresh.getAuthenticatorSelection())
                .attestation(fresh.getAttestation()).extensions(fresh.getExtensions())
                .build();
        assertThat(rebuilt.getChallenge()).isEqualTo(challenge);
    }

    @Test
    void loginRequestOptionsRoundTripWholeThroughRedis() {
        PublicKeyCredentialRequestOptions options = operations.createCredentialRequestOptions(
                new ImmutablePublicKeyCredentialRequestOptionsRequest(auth));
        RedisSession session = sessions.createSession();
        session.setAttribute(ATTR, options);
        sessions.save(session);

        PublicKeyCredentialRequestOptions loaded = sessions.findById(session.getId()).getAttribute(ATTR);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getChallenge()).isEqualTo(options.getChallenge());
        sessions.deleteById(session.getId());
    }
}
