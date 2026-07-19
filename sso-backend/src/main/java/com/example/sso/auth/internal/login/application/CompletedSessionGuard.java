package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.shared.error.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a login on a session that already finished one.
 *
 * <p>Re-running a login is not a logout. It re-establishes the security context and mints a NEW session id
 * without destroying anything, so no termination propagates and everything indexed under the OLD id —
 * back-channel-logout participants, the SAML SessionIndex, concurrency counters — is orphaned until it expires
 * on its own. Those downstream sessions can then never be logged out, not by the user and not by an
 * administrator forcing termination. Signing in as somebody else has to start with signing out.
 *
 * <p>Shared by every entry point rather than repeated at each: the pre-auth organization marker outlives
 * completion (nothing clears it, and a tenant subdomain re-arms it on the next probe anyway), so each entry
 * point is individually reachable from a signed-in browser and each one needs the same answer.
 */
@Component
@RequiredArgsConstructor
class CompletedSessionGuard {

    private final CurrentUserProvider currentUser;

    void refuseIfAlreadySignedIn() {
        Authentication current = currentUser.authentication();
        boolean complete = current != null && current.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Factors.MFA_COMPLETE::equals);
        if (complete) {
            throw BadRequestException.of("auth.signin.inProgress");
        }
    }
}
