package com.example.sso.config.internal;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.RoleRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * What an issued ID or access token asserts about the user and about HOW they authenticated.
 *
 * <p>Extracted from {@code AuthorizationServerConfig}, where it was a ninety-line lambda inside a
 * {@code @Bean} method. That shape is why the logic had no unit test: the RFC 8176 {@code amr} mapping, the
 * {@code acr} level, the epoch-second string encoding and the back-channel {@code sid} recording were all
 * reachable only by standing up the authorization server and completing a real flow. A claim other systems
 * make trust decisions on deserves an executable spec, and this class is one.
 *
 * <p>The behaviour is unchanged from the lambda, comments included — several of them record constraints that
 * are invisible and expensive: the JDBC authorization store round-trips these claims through a locked-down
 * Jackson mapper, so a time claim must be a String and a collection must be a mutable ArrayList. Both failures
 * appear only as a token-endpoint 500 on read-back, which no compiler catches.
 */
@RequiredArgsConstructor
class OidcTokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final UserService users;
    private final OidcBackchannelSessionIndex backchannelIndex;

    @Override
    public void customize(JwtEncodingContext context) {
        String username = context.getPrincipal().getName();
        users.findByUsername(username).ifPresent(user -> {
            boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
            boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());

            if (idToken && context.getAuthorizedScopes().contains(OidcScopes.PROFILE)) {
                context.getClaims().claim("name",
                        user.getDisplayName() != null ? user.getDisplayName() : username);
                context.getClaims().claim("preferred_username", username);
            }

            if (idToken && context.getAuthorizedScopes().contains(OidcScopes.EMAIL)) {
                context.getClaims().claim("email", user.getEmail());
                // Emitted BESIDE the address, never omitted. An address a federated upstream merely
                // asserted can name an account here, so an RP that keys on `email` has to be able to see
                // that nobody proved it — omitting the claim invites exactly the assumption we refuse
                // to make ourselves (see FederatedUserProvisioner).
                context.getClaims().claim("email_verified", user.isEmailVerified());
            }

            // Authentication-context claims so RPs (ID token) AND the admin elevation gate (access
            // token) can verify HOW (and how strongly) the user authenticated — RFC 8176 amr, an acr
            // level, and the OIDC auth_time. Emitted on both the ID token and the bearer access token:
            // the admin API requires acr=mfa + a fresh auth_time on the access token (RFC 9470 step-up).
            if (idToken || accessToken) {
                Set<String> auth = context.getPrincipal().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
                // Counted from THIS IdP's own vocabulary, not by the "FACTOR_" prefix. Spring Security
                // co-owns that namespace (FACTOR_AUTHORIZATION_CODE, FACTOR_SAML_RESPONSE, FACTOR_BEARER,
                // …) and mints those authorities from configurers this deployment does not currently wire.
                // Counting by prefix would let a future switch to Spring's own federation push a
                // single-factor login to acr=mfa and past the admin elevation gate — while naming nothing
                // in amr. Counting the known set keeps "empty amr <=> no factor" true by construction.
                long factorCount = auth.stream().filter(AuthFactor::isKnownAuthority).count();
                List<String> amr = AuthenticationMethodReferences.of(auth, factorCount);
                // acr rides on amr being non-empty, deliberately. Every factor this IdP can clear now
                // names itself in amr, so the only session that reports no method is one that cleared no
                // factor — and emitting `sfa` there would assert a strength level for a check nobody
                // performed. Do not "fix" this by emitting acr unconditionally.
                if (!amr.isEmpty()) {
                    context.getClaims().claim("amr", amr);
                    context.getClaims().claim("acr", factorCount >= 2 ? "mfa" : "sfa");
                }
                // Emit time claims as String (epoch seconds), NOT Long/Instant: the JDBC authorization
                // store re-serializes token claims through a locked-down Jackson mapper that REJECTS a
                // bare java.lang.Long (PolymorphicTypeValidator) AND cannot reflectively serialize a
                // java.time.Instant (JPMS InaccessibleObjectException) — both surface as a token-endpoint
                // 500 on read-back. A String round-trips cleanly (like acr/azp). The marker already holds
                // the epoch-seconds string, so we emit it verbatim; the admin gate parses it back.
                auth.stream().filter(a -> a.startsWith(Factors.AUTH_TIME_PREFIX)).findFirst()
                        .ifPresent(a -> context.getClaims().claim("auth_time",
                                a.substring(Factors.AUTH_TIME_PREFIX.length())));
                // stepup_time: present only after a DELIBERATE /reauth step-up (not a plain login),
                // so the admin elevation gate can require a recent re-authentication.
                auth.stream().filter(a -> a.startsWith(Factors.STEPUP_TIME_PREFIX)).findFirst()
                        .ifPresent(a -> context.getClaims().claim("stepup_time",
                                a.substring(Factors.STEPUP_TIME_PREFIX.length())));
                // `org`: the organization (tenant) id this session logged into (tenant-first entry), so a
                // relying party can scope the user to the tenant. String marker -> round-trips like acr.
                auth.stream().filter(a -> a.startsWith(Factors.ORG_PREFIX)).findFirst()
                        .ifPresent(a -> context.getClaims().claim("org",
                                a.substring(Factors.ORG_PREFIX.length())));
                // OIDC `sid` (id token only): identifies THIS OP session so back-channel logout can
                // target the exact session on expiry/logout, not every session of the subject. Record
                // the client as a participant of this session (the token endpoint has no HTTP session,
                // so the sid→clients map is captured here for the termination listener to fan out to).
                if (idToken) {
                    auth.stream().filter(a -> a.startsWith(Factors.SID_PREFIX)).findFirst()
                            .map(a -> a.substring(Factors.SID_PREFIX.length()))
                            .ifPresent(sid -> {
                                context.getClaims().claim("sid", sid);
                                // Record the client by its globally-unique internal id, NOT client_id: a
                                // client_id is unique only per tenant, so the browser-less logout path must
                                // resolve the owning org from the unambiguous id to sign/deliver to the right
                                // tenant.
                                backchannelIndex.record(sid, context.getRegisteredClient().getId(), username);
                            });
                }
            }

            if (accessToken) {
                // Use a mutable ArrayList, NOT Stream.toList()/List.of(): the JDBC authorization store
                // re-serializes token claims with Jackson polymorphic typing, and its security
                // PolymorphicTypeValidator rejects ImmutableCollections$ListN on read-back (token
                // endpoint 500). ArrayList is on Spring Security's Jackson allow-list.
                context.getClaims().claim("roles",
                        new ArrayList<>(user.getRoles().stream().map(RoleRef::getName).toList()));
                // Bind the bearer to the issuing client so the admin gate can pin it to admin-console.
                context.getClaims().claim("azp", context.getRegisteredClient().getClientId());
            }
        });
    }
}
