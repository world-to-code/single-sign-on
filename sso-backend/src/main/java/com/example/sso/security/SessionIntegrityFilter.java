package com.example.sso.security;

import com.example.sso.audit.AuditService;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Zero-Trust session integrity: an authenticated session cookie is never trusted on its own.
 * Every authenticated request re-verifies, against the admin-managed {@link SessionPolicy}, that
 * <ul>
 *   <li>the session has not exceeded its <b>absolute lifetime</b> (periodic full re-auth),</li>
 *   <li>the session has not been <b>idle</b> longer than the idle timeout, and</li>
 *   <li>the session is still bound to the same client (User-Agent) it was established on.</li>
 * </ul>
 * The User-Agent binding is a lightweight defence-in-depth signal, not strong session-theft
 * protection: the User-Agent is client-controlled and an attacker who has stolen the cookie can
 * usually replay the original header. It cheaply catches naive cookie replay across different
 * clients; it is not a substitute for the other controls above.
 * On violation the session is invalidated, the context cleared, the event audited, and the request
 * rejected.
 */
@Component
@RequiredArgsConstructor
public class SessionIntegrityFilter extends OncePerRequestFilter {

    private static final String CLIENT_BINDING = "ZT_CLIENT_BINDING";
    private static final String LAST_ACTIVITY = "ZT_LAST_ACTIVITY";

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final AuditService audit;
    private final SessionPolicyService policyService;
    private final SessionRegistry sessionRegistry;
    private final SessionMetadataStore sessionMetadata;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (session != null && isAuthenticated(authentication)) {
            String username = authentication.getName();
            SessionPolicyDetails policy = policyService.resolveForUsername(username);
            long now = System.currentTimeMillis();

            // Concurrent-session control: AuthApiController evicts the oldest overflow sessions by
            // marking them expired in the SessionRegistry. Mirror Spring's ConcurrentSessionFilter —
            // reject + invalidate an expired session here, otherwise refresh its last-request stamp
            // (which orders sessions for oldest-first eviction).
            SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
            if (info != null) {
                if (info.isExpired()) {
                    reject(session, response, "SESSION_CONCURRENT_EXPIRED", username);
                    return;
                }
                info.refreshLastRequest();
            }

            if (now - session.getCreationTime() > policy.getAbsoluteTimeoutMinutes() * 60_000L) {
                reject(session, response, "SESSION_EXPIRED_ABSOLUTE", username);
                return;
            }
            Object last = session.getAttribute(LAST_ACTIVITY);
            if (last instanceof Long lastMillis && now - lastMillis > policy.getIdleTimeoutMinutes() * 60_000L) {
                reject(session, response, "SESSION_EXPIRED_IDLE", username);
                return;
            }
            session.setAttribute(LAST_ACTIVITY, now);
            sessionMetadata.touch(session.getId()); // refresh "last seen" for the My Profile sessions list

            if (policy.isBindClient()) {
                String current = clientBinding(request);
                Object bound = session.getAttribute(CLIENT_BINDING);
                if (bound == null) {
                    session.setAttribute(CLIENT_BINDING, current);
                } else if (!bound.equals(current)) {
                    reject(session, response, "SESSION_CONTEXT_MISMATCH", username);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpSession session, HttpServletResponse response, String reason, String username)
            throws IOException {
        audit.record(reason, username, false);
        session.invalidate();
        contextHolder.clearContext();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.getWriter().write("Session is no longer valid. Please sign in again.");
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private String clientBinding(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return userAgent == null ? "" : userAgent;
    }
}
