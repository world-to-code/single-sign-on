package com.example.sso.security;

import com.example.sso.session.internal.lifecycle.application.SessionManagerImpl;
import com.example.sso.session.internal.policy.domain.SessionPolicy;
import com.example.sso.session.lifecycle.SessionLifecycle;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditService;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.lifecycle.StepUpInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

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

            // (Per-policy network/IP access is enforced separately by PolicyIpAccessFilter, which runs on
            //  BOTH security chains — the OIDC authorization-server chain is a separate chain this filter
            //  never sees.)

            // Drive the servlet container's idle timeout from the policy (a small grace so the precise
            // idle check below rejects first, with its audit); otherwise the fixed server.servlet.session
            // .timeout would silently cap any policy idle above it.
            session.setMaxInactiveInterval((int) (policy.getIdleTimeoutMinutes() * 60L + 60));

            // Concurrent-session control: SessionLifecycle (SessionManagerImpl) evicts the oldest overflow sessions by
            // marking them expired in the SessionRegistry. Mirror Spring's ConcurrentSessionFilter —
            // reject + invalidate an expired session here, otherwise refresh its last-request stamp
            // (which orders sessions for oldest-first eviction).
            SessionInformation info = sessionRegistry.getSessionInformation(session.getId());
            if (info != null) {
                if (info.isExpired()) {
                    reject(session, response, AuditType.SESSION_CONCURRENT_EXPIRED, username);
                    return;
                }
                info.refreshLastRequest();
            }

            if (now - session.getCreationTime() > policy.getAbsoluteTimeoutMinutes() * 60_000L) {
                reject(session, response, AuditType.SESSION_EXPIRED_ABSOLUTE, username);
                return;
            }

            Object last = session.getAttribute(LAST_ACTIVITY);
            if (last instanceof Long lastMillis && now - lastMillis > policy.getIdleTimeoutMinutes() * 60_000L) {
                reject(session, response, AuditType.SESSION_EXPIRED_IDLE, username);
                return;
            }

            // Idle-based MANDATORY re-authentication: after reauthIntervalMinutes with no (non-exempt) request,
            // the session must be re-verified before it is used again. Unlike idle/absolute expiry this does
            // NOT invalidate the session — it stays signed in but every protected request is refused with a
            // step-up challenge until the user re-authenticates, so a dismissed client modal cannot bypass it.
            // The auth/re-auth flow and the config the timers read are exempt (else lock-out); only a non-exempt
            // request refreshes the re-auth clock (which login and each re-auth also stamp).
            if (!isReauthExempt(request)) {
                Object lastReauth = session.getAttribute(StepUpInterceptor.REAUTH_ACTIVITY);
                long reauthGap = lastReauth instanceof Long r ? now - r : now - session.getCreationTime();
                if (reauthGap > policy.getReauthIntervalMinutes() * 60_000L) {
                    challengeReauth(session, response, policy.getReauthFactors());
                    return;
                }
                session.setAttribute(StepUpInterceptor.REAUTH_ACTIVITY, now);
            }

            session.setAttribute(LAST_ACTIVITY, now);
            sessionMetadata.touch(session.getId()); // refresh "last seen" for the My Profile sessions list

            if (policy.isBindClient()) {
                String current = clientBinding(request);
                Object bound = session.getAttribute(CLIENT_BINDING);
                if (bound == null) {
                    session.setAttribute(CLIENT_BINDING, current);
                } else if (!bound.equals(current)) {
                    reject(session, response, AuditType.SESSION_CONTEXT_MISMATCH, username);
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpSession session, HttpServletResponse response, AuditType reason, String username)
            throws IOException {
        audit.record(reason, username, false);
        session.invalidate();
        contextHolder.clearContext();

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.getWriter().write("Session is no longer valid. Please sign in again.");
    }

    /**
     * Only what is needed to (re-)authenticate or render the prompt stays reachable while re-auth is due:
     * the re-auth flow, session bootstrap, logout, the timers' config read, and SPA/static GETs. Sensitive
     * self-service (factor/passkey enrollment, session management) is intentionally NOT exempt, so a session
     * idle past the interval must re-authenticate before it can be used for those. The login flow runs before
     * the session is authenticated, so it is not subject to this gate.
     */
    private boolean isReauthExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            // SPA assets / non-API GETs must load so the modal can render — but the built-in /webauthn/register
            // passkey enrollment (an authenticated, sensitive mutation) is gated.
            return !path.startsWith("/webauthn/register");
        }
        return path.startsWith("/api/auth/reauth/")
                || path.equals("/api/auth/session")
                || path.equals("/api/auth/logout")
                // Re-proving an address an admin changed: the mandatory re-auth may demand the EMAIL factor,
                // which is refused while that address is unverified. Challenging the recovery path too would
                // soft-brick the session — the only escape would be to sign out. Matched EXACTLY (like the
                // other entries), never by prefix: getRequestURI() is un-normalized, so a prefix could exempt
                // a crafted URI that routes elsewhere.
                || path.equals("/api/auth/email-verification")
                || path.equals("/api/auth/email-verification/confirm")
                || path.equals("/api/portal/session-config");
    }

    /** Refuse the request with a step-up challenge WITHOUT invalidating — the session is alive, just stale. */
    private void challengeReauth(HttpSession session, HttpServletResponse response, String reauthFactors)
            throws IOException {
        session.setAttribute(StepUpInterceptor.STEPUP_FACTORS, reauthFactors); // ReauthService verifies against this
        String factors = Arrays.stream(reauthFactors.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("X-Step-Up-Required", "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"reauthRequired\":true,\"mandatory\":true,\"factors\":[" + factors + "]}");
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
