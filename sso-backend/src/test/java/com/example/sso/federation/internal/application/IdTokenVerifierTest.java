package com.example.sso.federation.internal.application;

import com.example.sso.shared.error.UnauthorizedException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IdTokenVerifier} against a locally-signed id_token — the security-critical validation
 * where an audience-confusion, replayed-nonce, wrong-issuer, unsigned, or expired token must all be rejected.
 * The JWKS fetch is bypassed by overriding {@code decoderFor} to verify against an in-memory RSA public key;
 * everything else (issuer / audience / nonce / subject / signature / expiry enforcement) is exercised for real.
 */
class IdTokenVerifierTest {

    private static final String ISSUER = "https://accounts.example.com";
    private static final String CLIENT_ID = "client-123";
    private static final String NONCE = "n-abc123";
    private static final OidcMetadata METADATA =
            new OidcMetadata(ISSUER, ISSUER + "/authorize", ISSUER + "/token", ISSUER + "/jwks");

    private RSAKey signingKey;
    private RSAKey otherKey; // a DIFFERENT key, to forge a signature the decoder won't trust
    private IdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k2").generate();
        // Decode against the real public key of `signingKey`, so only tokens signed by it verify.
        verifier = new IdTokenVerifier(Duration.ofSeconds(10)) {
            @Override
            NimbusJwtDecoder decoderFor(String jwksUri) {
                try {
                    return NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(CLIENT_ID).subject("sub-123")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .issueTime(Date.from(Instant.now()))
                .claim("nonce", NONCE).claim("email", "ada@example.com")
                .claim("email_verified", true).claim("name", "Ada");
    }

    private String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void aValidTokenYieldsTheClaims() throws Exception {
        VerifiedIdToken verified = verifier.verify(METADATA, CLIENT_ID, sign(signingKey, validClaims().build()), NONCE);

        assertThat(verified.subject()).isEqualTo("sub-123");
        assertThat(verified.email()).isEqualTo("ada@example.com");
        assertThat(verified.emailVerified()).isTrue();
        assertThat(verified.name()).isEqualTo("Ada");
    }

    @Test
    void anUnverifiedEmailIsCarriedThroughAsFalse() throws Exception {
        String token = sign(signingKey, validClaims().claim("email_verified", false).build());

        assertThat(verifier.verify(METADATA, CLIENT_ID, token, NONCE).emailVerified()).isFalse();
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        String token = sign(signingKey, validClaims().issuer("https://evil.example.com").build());

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() throws Exception {
        String token = sign(signingKey, validClaims().audience("some-other-client").build());

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aMismatchedNonceIsRejected() throws Exception {
        String token = sign(signingKey, validClaims().build()); // carries NONCE

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, "a-different-nonce"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aMissingNonceIsRejected() throws Exception {
        String token = sign(signingKey, validClaims().claim("nonce", null).build());

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String token = sign(signingKey, validClaims()
                .expirationTime(Date.from(Instant.now().minusSeconds(60))).build());

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aTokenSignedByAnUntrustedKeyIsRejected() throws Exception {
        String token = sign(otherKey, validClaims().build()); // signature won't verify against signingKey

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aTokenWithNoSubjectIsRejected() throws Exception {
        String token = sign(signingKey, validClaims().subject(null).build());

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anUnsignedAlgNoneTokenIsRejected() {
        // alg=none: no signature to verify against the JWKS. The decoder must refuse it (no key), never trust it.
        String token = new PlainJWT(validClaims().build()).serialize();

        assertThatThrownBy(() -> verifier.verify(METADATA, CLIENT_ID, token, NONCE))
                .isInstanceOf(UnauthorizedException.class);
    }
}
