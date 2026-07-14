package com.example.sso.session.lifecycle;

import com.example.sso.session.policy.ConsoleSessionPolicy;
import com.example.sso.session.policy.EffectiveSessionPolicy;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.UserSessionPolicy;

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
    /** Epoch-millis of the last activity, for the idle-based general re-auth clock. */
    public static final String REAUTH_ACTIVITY = "SSO_REAUTH_ACTIVITY";
    /** Epoch-millis of the last DELIBERATE step-up (only a /reauth sets it — a plain login never does). */
    public static final String STEPUP_TIME = "SSO_STEPUP_TIME";
    /** The factor that satisfied that step-up — checked against the policy's stepUpFactors (strength floor). */
    public static final String STEPUP_FACTOR = "SSO_STEPUP_FACTOR";
    /** The factor set a pending step-up must be satisfied with (set on challenge; enforced by ReauthService). */
    public static final String STEPUP_FACTORS = "SSO_STEPUP_FACTORS";

    private static final Set<String> MUTATING = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name(), HttpMethod.PATCH.name());

    private final SessionPolicyService policyService;
    private final UserSessionPolicy userSessionPolicy;
    private final ConsoleSessionPolicy consoleSessionPolicy;

    public StepUpInterceptor(SessionPolicyService policyService, UserSessionPolicy userSessionPolicy,
            ConsoleSessionPolicy consoleSessionPolicy) {
        this.policyService = policyService;
        this.userSessionPolicy = userSessionPolicy;
        this.consoleSessionPolicy = consoleSessionPolicy;
    }

    /** Records general activity (login / any allowed request) on the idle-based re-auth clock — NOT a step-up. */
    public static void stamp(HttpSession session) {
        if (session != null) {
            session.setAttribute(REAUTH_ACTIVITY, System.currentTimeMillis());
        }
    }

    /**
     * Records a DELIBERATE step-up re-auth with the factor that satisfied it (also counts as activity). Only
     * this — never a plain login — makes a sensitive action pass, and only if {@code factor} is a step-up factor.
     */
    public static void stampStepUp(HttpSession session, String factor) {
        if (session != null) {
            long now = System.currentTimeMillis();
            session.setAttribute(STEPUP_TIME, now);
            session.setAttribute(STEPUP_FACTOR, factor);
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

        if (sensitive) {
            // Sensitive admin actions follow the ADMIN CONSOLE's policy (its PORTAL/admin selection, else the
            // user's own), so an admin can require STRONGER or FRESHER step-up for destructive actions than a
            // regular user. The general mutation re-auth below uses the same effective re-auth cadence/factors
            // SessionIntegrityFilter enforces per SESSION (one session).
            SessionPolicyDetails consolePolicy = authentication == null ? policyService.defaultPolicy()
                    : consoleSessionPolicy.resolveForConsole(authentication.getName());
            // Requires a fresh DELIBERATE step-up (not a plain login) within the window AND that its factor
            // is one of the policy's step-up factors — so stepUpFactors is a real strength floor, not advisory.
            long sinceStepUp = attr(session, STEPUP_TIME) instanceof Long t ? now - t : Long.MAX_VALUE;
            boolean strongEnough = attr(session, STEPUP_FACTOR) instanceof String f
                    && csv(consolePolicy.getStepUpFactors()).contains(f);
            if (strongEnough && sinceStepUp <= consolePolicy.getSensitiveReauthWindowMinutes() * 60_000L) {
                touchActivity(session, now);
                return true;
            }
            return challenge(session, response, consolePolicy.getStepUpFactors());
        }

        // General mutation: idle-based, on the EFFECTIVE re-auth cadence/factors (the specificity winner's) — the
        // same resolution SessionIntegrityFilter uses, so this gate and the filter never disagree. A challenged
        // request does NOT refresh the clock (so it can't be bypassed by retrying); only allowed requests and
        // re-auths do. Null clock -> fail closed.
        int reauthIntervalMinutes;
        String reauthFactors;
        if (authentication == null) {
            SessionPolicyDetails dflt = policyService.defaultPolicy();
            reauthIntervalMinutes = dflt.getReauthIntervalMinutes();
            reauthFactors = dflt.getReauthFactors();
        } else {
            EffectiveSessionPolicy effective = userSessionPolicy.effectiveForUsername(authentication.getName());
            reauthIntervalMinutes = effective.reauthIntervalMinutes();
            reauthFactors = effective.reauthFactors();
        }
        long idleGap = attr(session, REAUTH_ACTIVITY) instanceof Long t ? now - t : Long.MAX_VALUE;
        if (idleGap <= reauthIntervalMinutes * 60_000L) {
            touchActivity(session, now);
            return true;
        }
        return challenge(session, response, reauthFactors);
    }

    private Object attr(HttpSession session, String name) {
        return session == null ? null : session.getAttribute(name);
    }

    private Set<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private void touchActivity(HttpSession session, long now) {
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
