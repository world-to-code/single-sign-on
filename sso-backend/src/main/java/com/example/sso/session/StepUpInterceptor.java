package com.example.sso.session;

import com.example.sso.shared.security.RequireStepUp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step-up re-authentication for sensitive operations, per the session policy.
 *
 * <p><b>Ordinary mutations</b> (POST/PUT/DELETE/PATCH) are gated by the policy's re-auth interval on an
 * <em>idle</em> basis: activity keeps the clock fresh, so an actively-used session is never re-challenged;
 * only after the session has been inactive longer than {@code reauthIntervalMinutes} does the next mutation
 * require a step-up. A read refreshes the activity clock but is never itself gated.
 *
 * <p><b>Sensitive actions</b> ({@link RequireStepUp} — destructive or privilege-escalating) instead require
 * a FRESH deliberate (re-)authentication within {@code sensitiveReauthWindowMinutes}, and are challenged
 * with the policy's (potentially stronger) {@code stepUpFactors}.
 *
 * <p>On failure the response is {@code 401} with {@code X-Step-Up-Required} and the allowed factors; the
 * SPA prompts for a fresh factor and retries via {@code /api/auth/reauth}, which re-stamps the clocks.
 */
@Component
public class StepUpInterceptor implements HandlerInterceptor {
    /** Session attribute holding the epoch-millis of the last full/step-up authentication. */
    public static final String AUTH_TIME = "SSO_AUTH_TIME";
    /** Epoch-millis of the last activity, for the idle-based general re-auth clock. */
    public static final String REAUTH_ACTIVITY = "SSO_REAUTH_ACTIVITY";
    /** The factor set a pending step-up must be satisfied with (set on challenge; enforced by ReauthService). */
    public static final String STEPUP_FACTORS = "SSO_STEPUP_FACTORS";

    private static final Set<String> MUTATING = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name(), HttpMethod.PATCH.name());

    private final SessionPolicyService policyService;

    public StepUpInterceptor(SessionPolicyService policyService) {
        this.policyService = policyService;
    }

    /** Records a successful (re-)authentication: refreshes both the auth-freshness and activity clocks. */
    public static void stamp(HttpSession session) {
        if (session != null) {
            long now = System.currentTimeMillis();
            session.setAttribute(AUTH_TIME, now);
            session.setAttribute(REAUTH_ACTIVITY, now);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        boolean sensitive = handler instanceof HandlerMethod method
                && method.getMethodAnnotation(RequireStepUp.class) != null;
        boolean mutating = MUTATING.contains(request.getMethod());
        HttpSession session = request.getSession(false);
        long now = System.currentTimeMillis();

        // A non-sensitive read is not gated, but DOES count as activity that keeps the idle clock fresh.
        if (!sensitive && !mutating) {
            touchActivity(session, now);
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionPolicyDetails policy = authentication == null
                ? policyService.defaultPolicy()
                : policyService.resolveForUsername(authentication.getName());

        if (sensitive) {
            // Fresh deliberate (re-)auth required within the policy's step-up window (null clock -> fail closed).
            long sinceAuth = attr(session, AUTH_TIME) instanceof Long t ? now - t : Long.MAX_VALUE;
            if (sinceAuth <= policy.getSensitiveReauthWindowMinutes() * 60_000L) {
                touchActivity(session, now);
                return true;
            }
            return challenge(session, response, policy.getStepUpFactors());
        }

        // General mutation: idle-based. A challenged request does NOT refresh the clock (so it can't be
        // bypassed by retrying); only allowed requests and re-auths do.
        long idleGap = attr(session, REAUTH_ACTIVITY) instanceof Long t ? now - t : 0L;
        if (idleGap <= policy.getReauthIntervalMinutes() * 60_000L) {
            touchActivity(session, now);
            return true;
        }
        return challenge(session, response, policy.getReauthFactors());
    }

    private static Object attr(HttpSession session, String name) {
        return session == null ? null : session.getAttribute(name);
    }

    private static void touchActivity(HttpSession session, long now) {
        if (session != null) {
            session.setAttribute(REAUTH_ACTIVITY, now);
        }
    }

    private boolean challenge(HttpSession session, HttpServletResponse response, String factorsCsv)
            throws IOException {
        if (session != null) {
            session.setAttribute(STEPUP_FACTORS, factorsCsv); // ReauthService verifies against exactly this set
        }
        String factors = Arrays.stream(factorsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("X-Step-Up-Required", "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"reauthRequired\":true,\"factors\":[" + factors + "]}");
        return false;
    }
}
