package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.BackChannelLogout;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Builds a signed OIDC Back-Channel Logout 1.0 {@code logout_token}. Signs through the SAME {@link JwtEncoder}
 * the Authorization Server uses (backed by the tenant-following JWK source, active key first), and stamps
 * the {@code issuer} the client's tokens were issued under, so a relying party validates it against its own
 * (per-tenant) issuer + JWKS. Carries {@code sid} for per-session logout, or omits it for subject-wide.
 */
@Component
public class LogoutTokenFactory {

    private final JwtEncoder encoder;

    public LogoutTokenFactory(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public String create(String clientId, String subject, String sid, String issuer) {
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
