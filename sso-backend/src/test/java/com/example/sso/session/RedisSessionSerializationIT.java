package com.example.sso.session;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-1 gate: a fully-authenticated {@code SecurityContext} — with the exact authority types our login
 * grants ({@code MFA_COMPLETE}, factor markers, an {@code AUTH_TIME_} marker, and a
 * {@link FactorGrantedAuthority}) and a {@link User} principal — must survive the round trip through Redis
 * (JDK serialization), and the session must be discoverable by principal name (the index that backs
 * concurrent-session control now that {@code SessionRegistry} is Spring-Session-backed).
 */
class RedisSessionSerializationIT extends AbstractIntegrationTest {

    static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    @Autowired
    RedisIndexedSessionRepository sessions;

    @Test
    void fullyAuthenticatedContextSurvivesRedisRoundTripAndIsIndexedByPrincipal() {
        String username = "serialization-probe-user";
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(Factors.MFA_COMPLETE),
                new SimpleGrantedAuthority(Factors.PASSWORD),
                new SimpleGrantedAuthority(Factors.AUTH_TIME_PREFIX + Instant.now().getEpochSecond()),
                FactorGrantedAuthority.withAuthority(Factors.PASSWORD).issuedAt(Instant.now()).build());
        var principal = User.withUsername(username).password("").authorities(authorities).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);

        RedisSession session = sessions.createSession();
        session.setAttribute(SECURITY_CONTEXT_ATTR, new SecurityContextImpl(auth));
        sessions.save(session);

        RedisSession loaded = sessions.findById(session.getId());
        assertThat(loaded).as("session round-trips out of Redis").isNotNull();
        SecurityContextImpl context = loaded.getAttribute(SECURITY_CONTEXT_ATTR);
        assertThat(context).as("SecurityContext deserializes").isNotNull();
        assertThat(context.getAuthentication().getName()).isEqualTo(username);
        assertThat(context.getAuthentication().getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains(Factors.MFA_COMPLETE, Factors.PASSWORD);
        assertThat(context.getAuthentication().getAuthorities())
                .as("the custom FactorGrantedAuthority survives JDK serialization")
                .anyMatch(FactorGrantedAuthority.class::isInstance);

        // The principal-name index (backing concurrent-session control) must resolve the session.
        assertThat(sessions.findByPrincipalName(username).keySet()).contains(session.getId());

        sessions.deleteById(session.getId());
    }
}
