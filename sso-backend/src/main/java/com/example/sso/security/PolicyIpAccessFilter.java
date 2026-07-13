package com.example.sso.security;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditService;
import com.example.sso.session.policy.UserSessionPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-policy network access control. For an authenticated request, refuses a denied network with 403 —
 * WITHOUT invalidating the session (it is valid, just not from this network). The IP allowlist is a FLOOR:
 * {@link UserSessionPolicy#isRemoteAllowed} requires the request to pass EVERY session policy governing the
 * user, so a narrow lax policy cannot open a network a broad org-wide allowlist blocks. Runs POST-authentication.
 *
 * <p>Registered on BOTH security chains (the app chain AND the OAuth2 authorization-server chain), because
 * they are separate {@code SecurityFilterChain}s: gating only the app chain would leave OIDC SSO
 * (/oauth2/authorize → downstream apps) reachable from a blocked network.
 */
@RequiredArgsConstructor
public class PolicyIpAccessFilter extends OncePerRequestFilter {

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final UserSessionPolicy userSessionPolicy;
    private final AuditService audit;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (isAuthenticated(authentication)
                && !userSessionPolicy.isRemoteAllowed(authentication.getName(), request.getRemoteAddr())) {
            audit.record(AuditType.IP_BLOCKED, authentication.getName(), false);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("Access from your network is not permitted.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
