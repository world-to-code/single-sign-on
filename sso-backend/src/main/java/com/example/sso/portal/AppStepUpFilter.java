package com.example.sso.portal;

import com.example.sso.authpolicy.Factors;
import com.example.sso.user.AppUser;
import com.example.sso.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces a per-application authentication policy at the OIDC authorization endpoint: a fully
 * signed-in user whose requested client requires extra (step-up) factors is redirected to the SPA
 * {@code /stepup} page, which collects the missing factor and resumes the saved authorize request.
 */
@RequiredArgsConstructor
public class AppStepUpFilter extends OncePerRequestFilter {

    public static final String RETURN = "APP_STEPUP_RETURN";
    public static final String APP_TYPE = "APP_STEPUP_TYPE";
    public static final String APP_ID = "APP_STEPUP_ID";
    /** Prefix for the per-app "last deliberate step-up" epoch-millis, keyed by app so a step-up for
     *  one app never satisfies another app's freshness window. */
    private static final String STEPUP_TIME_PREFIX = "APP_STEPUP_TIME::";

    /** Session attribute key holding the last deliberate step-up time for a specific app. */
    public static String stepUpTimeKey(AppAssignment.AppType type, String appId) {
        return STEPUP_TIME_PREFIX + type + ":" + appId;
    }

    /** The last deliberate step-up recorded for this specific app, or null if none. */
    public static Instant lastAppStepUp(HttpSession session, AppAssignment.AppType type, String appId) {
        Object value = session == null ? null : session.getAttribute(stepUpTimeKey(type, appId));
        return value instanceof Long millis ? Instant.ofEpochMilli(millis) : null;
    }

    private final RegisteredClientRepository registeredClients;
    private final UserService users;
    private final ApplicationService applications;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/oauth2/authorize".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String clientId = request.getParameter("client_id");
        if (!isFullyAuthenticated(auth) || clientId == null) {
            chain.doFilter(request, response); // unauthenticated -> normal login redirect handles it
            return;
        }
        RegisteredClient client = registeredClients.findByClientId(clientId);
        AppUser user = users.findByUsername(auth.getName()).orElse(null);
        if (client == null || user == null) {
            chain.doFilter(request, response);
            return;
        }
        Set<String> granted = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("FACTOR_")).collect(Collectors.toSet());
        AppAccess access = applications.appAccess(user, AppAssignment.AppType.OIDC, client.getId(), granted,
                lastAppStepUp(request.getSession(false), AppAssignment.AppType.OIDC, client.getId()));
        if (access.ready()) {
            chain.doFilter(request, response);
            return;
        }
        String query = request.getQueryString();
        HttpSession session = request.getSession(true);
        session.setAttribute(RETURN, request.getRequestURI() + (query != null ? "?" + query : ""));
        session.setAttribute(APP_TYPE, "OIDC");
        session.setAttribute(APP_ID, client.getId());
        response.sendRedirect(request.getContextPath() + "/stepup");
    }

    private boolean isFullyAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(Factors.MFA_COMPLETE::equals);
    }
}
