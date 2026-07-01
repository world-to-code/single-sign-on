package com.example.sso.mfa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/**
 * MFA module's public contract for establishing and upgrading the session {@link Authentication} of
 * the custom JSON auth flow (adding completed factor authorities, applying session-fixation
 * protection, and stamping re-auth freshness markers). The implementation stays module-internal.
 */
public interface FactorAuthorizationService {

    /**
     * Establishes a brand-new authenticated context (e.g. after password login) in the session,
     * rotating the session id first to defend against session-fixation.
     */
    void establish(HttpServletRequest request, HttpServletResponse response, Authentication authentication);

    boolean grantFactor(HttpServletRequest request, HttpServletResponse response, String factorAuthority);

    /**
     * Marks a successful DELIBERATE step-up re-auth on the session {@link Authentication}: refreshes
     * the login {@code AUTH_TIME} marker AND stamps a {@code STEPUP_TIME} marker for the current
     * epoch second (RFC 9470).
     */
    boolean restampAuthTime(HttpServletRequest request, HttpServletResponse response);
}
