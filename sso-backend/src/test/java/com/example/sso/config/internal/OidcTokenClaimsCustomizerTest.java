package com.example.sso.config.internal;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The claims an issued token asserts about HOW the user authenticated.
 *
 * <p>None of this had a JVM test before the customizer was extracted from its {@code @Bean} lambda — it was
 * reachable only by completing a real OIDC flow against a running server. The invariant that matters most is
 * the one that spans two inputs and therefore fell between the two existing unit tests: {@code acr} is derived
 * from a COUNT while {@code amr} is derived from a MAPPING, and they must be computed over the same
 * vocabulary. Reverting the count to a {@code FACTOR_} prefix test — the defect fixed in 25625445 — leaves
 * both of those tests green and only fails here.
 */
@ExtendWith(MockitoExtension.class)
class OidcTokenClaimsCustomizerTest {

    @Mock
    UserService users;
    @Mock
    OidcBackchannelSessionIndex backchannelIndex;
    @Mock
    UserAccount user;

    private JwtClaimsSet.Builder customize(OAuth2TokenType tokenType, String... authorities) {
        lenient().when(user.getDisplayName()).thenReturn("Alice");
        lenient().when(user.isEmailVerified()).thenReturn(true);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        Authentication principal = UsernamePasswordAuthenticationToken.authenticated("alice", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("alice");
        JwtEncodingContext context = JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), claims)
                .principal(principal)
                .registeredClient(RegisteredClient.withId("id").clientId("demo")
                        .redirectUri("https://rp.example/cb")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .build())
                .tokenType(tokenType)
                .authorizedScopes(Set.of())
                .build();

        new OidcTokenClaimsCustomizer(users, backchannelIndex).customize(context);
        return claims;
    }

    private Map<String, Object> claimsOf(OAuth2TokenType tokenType, String... authorities) {
        return customize(tokenType, authorities).build().getClaims();
    }

    /**
     * The joint invariant. `acr` says multi-factor exactly when `amr` NAMES at least two methods — the two are
     * computed from the same set or they are lying to each other. This is what neither
     * AuthenticationMethodReferencesTest (which is handed factorCount) nor AuthFactorTest (which tests the
     * predicate alone) can see.
     */
    @Test
    void acrSaysMultiFactorExactlyWhenAmrNamesTwoMethods() {
        Map<String, Object> single = claimsOf(OAuth2TokenType.ACCESS_TOKEN, Factors.PASSWORD);
        Map<String, Object> multi = claimsOf(OAuth2TokenType.ACCESS_TOKEN, Factors.PASSWORD, Factors.SMS);

        assertThat(single).containsEntry("acr", "sfa");
        assertThat(namedMethods(single)).hasSize(1);

        assertThat(multi).containsEntry("acr", "mfa");
        assertThat(namedMethods(multi)).hasSize(2);
    }

    /**
     * The exact string the count fix exists to ignore. Spring Security mints this in the same namespace; if
     * the count went back to a prefix test, a single-factor login would claim `mfa` here — and `acr=mfa` is
     * what the admin elevation gate accepts — while `amr` still named one method.
     */
    @Test
    void anAuthorityFromSpringSecuritysNamespaceNeitherCountsNorIsNamed() {
        Map<String, Object> claims =
                claimsOf(OAuth2TokenType.ACCESS_TOKEN, Factors.PASSWORD, "FACTOR_AUTHORIZATION_CODE");

        assertThat(claims).containsEntry("acr", "sfa");
        assertThat(namedMethods(claims)).containsExactly("pwd");
    }

    /** A session that cleared no factor asserts no strength level at all, rather than claiming `sfa`. */
    @Test
    void aSessionThatClearedNoFactorEmitsNeitherAmrNorAcr() {
        Map<String, Object> claims = claimsOf(OAuth2TokenType.ACCESS_TOKEN, "ROLE_USER");

        assertThat(claims).doesNotContainKeys("amr", "acr");
    }

    /**
     * The encoding is load-bearing and invisible: the JDBC authorization store re-serializes these claims
     * through a locked-down Jackson mapper that rejects a bare Long and cannot reflect over an Instant. Either
     * surfaces only as a token-endpoint 500 on read-back.
     */
    @Test
    void timeClaimsAreEmittedAsStrings() {
        Map<String, Object> claims = claimsOf(OAuth2TokenType.ACCESS_TOKEN,
                Factors.PASSWORD, Factors.AUTH_TIME_PREFIX + "1700000000");

        assertThat(claims.get("auth_time")).isInstanceOf(String.class).isEqualTo("1700000000");
    }

    /** Same trap, other half: the roles collection must be a mutable ArrayList, not List.of(). */
    @Test
    void theRolesClaimIsAMutableList() {
        Map<String, Object> claims = claimsOf(OAuth2TokenType.ACCESS_TOKEN, Factors.PASSWORD, "ROLE_ADMIN");

        Object roles = claims.get("roles");
        assertThat(roles).isInstanceOf(ArrayList.class);
    }

    /** Every factor this IdP can clear must name itself here too, not only in the mapper's own test. */
    @Test
    void everyFactorReachesAmrThroughTheCustomizer() {
        for (AuthFactor factor : AuthFactor.values()) {
            Map<String, Object> claims = claimsOf(OAuth2TokenType.ACCESS_TOKEN, factor.authority());

            assertThat(namedMethods(claims))
                    .as("factor %s must name itself through the customizer", factor)
                    .isNotEmpty();
        }
    }

    /** `mfa` is a strength assertion, not a method — the methods are what an RP counts. */
    @SuppressWarnings("unchecked")
    private List<String> namedMethods(Map<String, Object> claims) {
        List<String> amr = (List<String>) claims.getOrDefault("amr", List.of());
        return amr.stream().filter(value -> !"mfa".equals(value)).toList();
    }
}
