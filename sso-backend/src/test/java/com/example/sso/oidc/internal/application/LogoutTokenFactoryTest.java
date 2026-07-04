package com.example.sso.oidc.internal.application;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the logout_token: signed with the same RSA key the AS publishes, and carrying exactly the
 * OIDC Back-Channel Logout 1.0 claims — including the required back-channel-logout event and NO {@code nonce}.
 */
class LogoutTokenFactoryTest {

    private static final String ISSUER = "https://idp.example";
    private static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";

    private RSAKey key;
    private LogoutTokenFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        JWKSource<SecurityContext> jwkSource = (selector, context) -> selector.select(new JWKSet(key));
        factory = new LogoutTokenFactory(jwkSource, ISSUER);
    }

    private Jwt decode(String token) throws Exception {
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        return decoder.decode(token);
    }

    @Test
    void buildsAValidSidScopedLogoutToken() throws Exception {
        Jwt jwt = decode(factory.create("client-1", "user-sub", "sid-123"));

        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getAudience()).containsExactly("client-1");
        assertThat(jwt.getSubject()).isEqualTo("user-sub");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo("sid-123");
        assertThat(jwt.getClaimAsMap("events")).containsKey(EVENT);
        assertThat(jwt.getId()).as("jti").isNotBlank();
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.hasClaim("nonce")).as("logout_token must not carry a nonce").isFalse();
    }

    @Test
    void omitsSidForSubjectWideLogout() throws Exception {
        Jwt jwt = decode(factory.create("client-1", "user-sub", null));

        assertThat(jwt.hasClaim("sid")).isFalse();
        assertThat(jwt.getSubject()).isEqualTo("user-sub");
        assertThat(jwt.getClaimAsMap("events")).containsKey(EVENT);
    }
}
