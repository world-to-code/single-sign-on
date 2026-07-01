package com.example.sso.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step-up re-authentication for sensitive (mutating) operations: if more than the policy's
 * re-auth window has elapsed since the user last (re-)authenticated, the request is rejected with
 * {@code 401} and an {@code X-Step-Up-Required} header listing the allowed re-auth factors. The SPA
 * then prompts for a fresh factor (TOTP/passkey) and retries.
 */
@Component
@RequiredArgsConstructor
public class StepUpInterceptor implements HandlerInterceptor {
    /** Session attribute holding the epoch-millis of the last full/step-up authentication. */
    public static final String AUTH_TIME = "SSO_AUTH_TIME";

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final SessionPolicyService policyService;

    /** Records the time of a successful (re-)authentication on the session. */
    public static void stamp(HttpSession session) {
        if (session != null) {
            session.setAttribute(AUTH_TIME, System.currentTimeMillis());
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!MUTATING.contains(request.getMethod())) {
            return true; // reads are not sensitive
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionPolicyDetails policy = authentication == null
                ? policyService.defaultPolicy()
                : policyService.resolveForUsername(authentication.getName());
        HttpSession session = request.getSession(false);
        Object authTime = session == null ? null : session.getAttribute(AUTH_TIME);
        long elapsed = authTime instanceof Long t ? System.currentTimeMillis() - t : Long.MAX_VALUE;
        if (elapsed <= policy.getReauthIntervalMinutes() * 60_000L) {
            return true; // recently (re-)authenticated
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
