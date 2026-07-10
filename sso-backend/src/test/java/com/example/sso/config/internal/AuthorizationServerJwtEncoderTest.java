package com.example.sso.config.internal;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JoseHeaderNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the {@code jwtEncoder} bean: the JWKS keeps rotated-away keys published (verification
 * overlap across rotation), so a signing selection matches SEVERAL keys — without a configured selector
 * {@code NimbusJwtEncoder} refuses ("multiple keys"). The bean must sign with the FIRST key of the set,
 * which {@code buildJwkSet} orders as the ACTIVE one.
 */
class AuthorizationServerJwtEncoderTest {

    @Test
    void signsWithTheFirstKeyWhenRotatedAwayKeysStayPublished() throws Exception {
        RSAKey active = new RSAKeyGenerator(2048).keyID("kid-active").generate();
        RSAKey retired = new RSAKeyGenerator(2048).keyID("kid-retired").generate();
        JWKSource<SecurityContext> jwkSource =
                (selector, context) -> selector.select(new JWKSet(List.of(active, retired)));
        JwtEncoder encoder = new AuthorizationServerConfig().jwtEncoder(jwkSource);

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().subject("user-sub").build()));

        assertThat(jwt.getHeaders().get(JoseHeaderNames.KID)).isEqualTo("kid-active");
        // And the signature really is the active key's — the retired key's public part must not verify it.
        Jwt decoded = NimbusJwtDecoder.withPublicKey(active.toRSAPublicKey()).build()
                .decode(jwt.getTokenValue());
        assertThat(decoded.getSubject()).isEqualTo("user-sub");
    }
}
