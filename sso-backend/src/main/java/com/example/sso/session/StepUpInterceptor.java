package com.example.sso.session;

import com.example.sso.shared.security.RequireStepUp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step-up re-authentication for sensitive operations.
 *
 * <p>Ordinary mutations (POST/PUT/DELETE/PATCH) are gated by the session policy's re-auth window: if more
 * than {@code reauthIntervalMinutes} has elapsed since the user last (re-)authenticated, the request is
 * rejected. A method annotated {@link RequireStepUp} (destructive or privilege-escalating actions —
 * deletes, policy edits, permission/role grants, key/secret rotation) is held to the STRICTER of its own
 * {@code maxAgeSeconds} and the policy window, so it demands a re-auth made immediately before the action.
 *
 * <p>On failure the response is {@code 401} with an {@code X-Step-Up-Required} header listing the allowed
 * re-auth factors; the SPA prompts for a fresh factor (TOTP/passkey) and retries, which re-stamps the auth
 * time and proceeds.
 */
@Component
public class StepUpInterceptor implements HandlerInterceptor {
    /** Session attribute holding the epoch-millis of the last full/step-up authentication. */
    public static final String AUTH_TIME = "SSO_AUTH_TIME";

    private static final Set<String> MUTATING = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name(), HttpMethod.PATCH.name());

    private final SessionPolicyService policyService;
    /** Freshness window for {@link RequireStepUp} operations (tunable; see {@code application.yml}). */
    private final Duration sensitiveMaxAge;

    public StepUpInterceptor(SessionPolicyService policyService,
                             @Value("${sso.security.step-up.sensitive-max-age}") Duration sensitiveMaxAge) {
        this.policyService = policyService;
        this.sensitiveMaxAge = sensitiveMaxAge;
    }

    /** Records the time of a successful (re-)authentication on the session. */
    public static void stamp(HttpSession session) {
        if (session != null) {
            session.setAttribute(AUTH_TIME, System.currentTimeMillis());
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        RequireStepUp requireStepUp = handler instanceof HandlerMethod method
                ? method.getMethodAnnotation(RequireStepUp.class) : null;

        // A non-annotated read carries no step-up requirement; annotated methods are always gated.
        if (requireStepUp == null && !MUTATING.contains(request.getMethod())) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionPolicyDetails policy = authentication == null
                ? policyService.defaultPolicy()
                : policyService.resolveForUsername(authentication.getName());

        long policyWindowMillis = policy.getReauthIntervalMinutes() * 60_000L;
        // Sensitive actions use the stricter (shorter) of the configured window and the policy window.
        long windowMillis = requireStepUp == null
                ? policyWindowMillis
                : Math.min(sensitiveMaxAge.toMillis(), policyWindowMillis);

        HttpSession session = request.getSession(false);
        Object authTime = session == null ? null : session.getAttribute(AUTH_TIME);
        long elapsed = authTime instanceof Long t ? System.currentTimeMillis() - t : Long.MAX_VALUE;
        if (elapsed <= windowMillis) {
            return true; // recently (re-)authenticated within the required window
        }

        String factors = Arrays.stream(policy.getReauthFactors().split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("X-Step-Up-Required", "true");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"reauthRequired\":true,\"factors\":[" + factors + "]}");
        return false;
    }
}
