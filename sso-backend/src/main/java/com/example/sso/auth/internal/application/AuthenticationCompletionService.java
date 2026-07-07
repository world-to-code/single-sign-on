package com.example.sso.auth.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.session.SessionLifecycle;
import com.example.sso.session.StepUpInterceptor;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.LoginResolutionScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

/**
 * Upgrades a session to fully-authenticated once the user's authentication policy is satisfied: loads
 * the user's real authorities, preserves the granted factor markers, adds {@code MFA_COMPLETE} and the
 * {@code auth_time} marker, registers the session (enforcing the concurrent-session limit) and audits
 * the completed sign-in. Extracted from the controller so the presentation layer stays thin.
 */
@Service
@RequiredArgsConstructor
public class AuthenticationCompletionService {

    private final AuthStateService authState;
    private final UserDetailsService userDetailsService;
    private final FactorAuthorizationService factorAuth;
    private final SessionLifecycle sessions;
    private final PreAuthOrgSession preAuthOrg;
    private final LoginResolutionScope loginScope;
    private final OrgContext orgContext;
    private final AuditService audit;

    /**
     * If the policy is satisfied and the session is not already complete, promotes it and returns the
     * refreshed view; otherwise returns the current view unchanged. Safe to call after any login step.
     */
    public AuthSessionView completeIfSatisfied(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return authState.describe(authentication,
                    preAuthOrg.orgSlug(request).orElse(null), preAuthOrg.orgId(request).orElse(null));
        }

        boolean alreadyComplete = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet()).contains(Factors.MFA_COMPLETE);

        if (!alreadyComplete && authState.isPolicySatisfied(authentication, preAuthOrg.orgId(request).orElse(null))) {
            // Resolve the FINAL session authorities bound to the LOGIN org, so the user's global roles AND
            // their roles in THIS org (RLS-scoped) both resolve — and no other org's roles leak in. This is
            // the single chokepoint every login path (password, passkey, factor) funnels through.
            // Re-resolve the user WITHIN the login's organization, so a username shared across organizations
            // maps to THIS tenant's account (falling back to a global super-admin) when authorities are loaded.
            UUID loginOrg = preAuthOrg.orgId(request).orElse(null);
            UserDetails principal = preAuthOrg.orgId(request)
                    .map(orgId -> orgContext.callInOrg(orgId, () -> loginScope.within(loginOrg,
                            () -> userDetailsService.loadUserByUsername(authentication.getName()))))
                    .orElseGet(() -> loginScope.within(loginOrg,
                            () -> userDetailsService.loadUserByUsername(authentication.getName())));
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(principal.getAuthorities());
            authentication.getAuthorities().stream()
                    .filter(a -> a.getAuthority().startsWith(Factors.FACTOR_PREFIX)).forEach(authorities::add);
            authorities.add(new SimpleGrantedAuthority(Factors.MFA_COMPLETE));
            // Carry the authentication time as a marker authority so the OIDC token customizer can emit
            // the standard `auth_time` claim. (A details object would break JdbcOAuth2AuthorizationService,
            // whose Jackson validator rejects arbitrary types; GrantedAuthority serializes fine.)
            authorities.add(new SimpleGrantedAuthority(Factors.AUTH_TIME_PREFIX + Instant.now().getEpochSecond()));
            // Stable per-session id surfaced as the OIDC `sid` claim (back-channel logout targets it). Set
            // once here (login completion runs once — MFA_COMPLETE gates re-entry); carried across re-auth.
            authorities.add(new SimpleGrantedAuthority(Factors.SID_PREFIX + UUID.randomUUID()));
            // Bind the session to the organization resolved at the tenant-first entry step, surfaced as the
            // `org` claim/attribute and used to scope the request's tenant context.
            preAuthOrg.orgId(request)
                    .ifPresent(orgId -> authorities.add(new SimpleGrantedAuthority(Factors.ORG_PREFIX + orgId)));
            // Spring Authorization Server derives the OIDC `auth_time` from a FactorGrantedAuthority's
            // issuedAt (JwtGenerator asserts one is present, else the token endpoint 500s). Our custom
            // flow uses string factor markers, so add one explicitly — reusing an existing factor
            // authority so amr/acr (computed from the FACTOR_ set) are unaffected.
            authorities.add(FactorGrantedAuthority.withAuthority(anyFactorAuthority(authorities))
                    .issuedAt(Instant.now()).build());
            factorAuth.establish(request, response,
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
            StepUpInterceptor.stamp(request.getSession(false)); // count login as activity (idle clock), NOT a step-up
            // Enforce the concurrent-session limit under the login org, so the tenant's own session policy
            // (not just the global default) governs how many sessions its members may hold.
            preAuthOrg.orgId(request).ifPresentOrElse(
                    orgId -> orgContext.runInOrg(orgId, () -> sessions.registerAndEnforceLimit(request, principal.getUsername())),
                    () -> sessions.registerAndEnforceLimit(request, principal.getUsername()));
            audit.record(new AuditRecord(AuditType.SESSION_CREATED, principal.getUsername(), true, null,
                    ClientIp.of(request), preAuthOrg.orgId(request).orElse(null))); // tenant-tag the completed sign-in
        }

        return authState.describe(SecurityContextHolder.getContext().getAuthentication(),
                preAuthOrg.orgSlug(request).orElse(null), preAuthOrg.orgId(request).orElse(null));
    }

    /** An existing factor authority to label the FactorGrantedAuthority with, so amr/acr stay unchanged. */
    private String anyFactorAuthority(Set<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Factors.FACTOR_PREFIX))
                .findFirst().orElse(Factors.PASSWORD);
    }
}
