package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.BackChannelLogout;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Builds a signed OIDC Back-Channel Logout 1.0 {@code logout_token}. Signs with the SAME rotatable RSA key
 * the Authorization Server uses (the shared {@link JWKSource}), so relying parties validate it against our
 * published {@code /oauth2/jwks}. Carries {@code sid} for per-session logout, or omits it for subject-wide.
 */
@Component
public class LogoutTokenFactory {

    private final JwtEncoder encoder;
    private final String issuer;

    public LogoutTokenFactory(JWKSource<SecurityContext> jwkSource, @Value("${sso.issuer}") String issuer) {
        this.encoder = new NimbusJwtEncoder(jwkSource);
        this.issuer = issuer;
    }

    public String create(String clientId, String subject, String sid) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(clientId))
                .issuedAt(now)
                .id(UUID.randomUUID().toString())
                .subject(subject)
                // The required back-channel-logout event; an empty JSON object per the spec. No `nonce`.
                .claim(BackChannelLogout.EVENTS_CLAIM, Map.of(BackChannelLogout.EVENT_TYPE, Map.of()));
        if (sid != null) {
            claims.claim("sid", sid);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
