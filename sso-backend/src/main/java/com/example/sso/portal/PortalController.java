package com.example.sso.portal;

import com.example.sso.session.SessionPolicy;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.user.AppUser;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** End-user portal API: the applications the signed-in user may launch via SSO, and step-up state. */
@RestController
@RequestMapping("/api/portal")
public class PortalController {

    private final ApplicationService applications;
    private final UserService users;
    private final SessionPolicyService sessionPolicy;

    public PortalController(ApplicationService applications, UserService users, SessionPolicyService sessionPolicy) {
        this.applications = applications;
        this.users = users;
        this.sessionPolicy = sessionPolicy;
    }

    /** Session timers for the SPA to enforce client-side: idle logout + periodic re-authentication. */
    @GetMapping("/session-config")
    public Map<String, Object> sessionConfig(Authentication authentication) {
        SessionPolicy p = sessionPolicy.resolveForUsername(authentication.getName());
        List<String> reauthFactors = Arrays.stream(p.getReauthFactors().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return Map.of(
                "idleTimeoutMinutes", p.getIdleTimeoutMinutes(),
                "reauthIntervalMinutes", p.getReauthIntervalMinutes(),
                "reauthFactors", reauthFactors);
    }

    @GetMapping("/apps")
    public List<ApplicationView> myApps(Authentication authentication) {
        return applications.appsForUser(requireUser(authentication));
    }

    /**
     * State for the /stepup page: re-evaluates the pending app launch (stored in session by the
     * step-up filter / SAML endpoint) and reports whether it's ready or which factor is still needed.
     */
    @GetMapping("/stepup")
    public StepUpInfo stepup(Authentication authentication, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String returnUrl = attr(session, AppStepUpFilter.RETURN, "/");
        String type = attr(session, AppStepUpFilter.APP_TYPE, null);
        String appId = attr(session, AppStepUpFilter.APP_ID, null);
        if (type == null || appId == null) {
            return new StepUpInfo(true, List.of(), returnUrl);
        }
        Set<String> granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("FACTOR_")).collect(Collectors.toSet());
        AppAccess access = applications.appAccess(requireUser(authentication),
                AppAssignment.AppType.valueOf(type), appId, granted);
        if (access.ready()) {
            // Clear the pending step-up so a later /stepup visit doesn't auto-redirect into a prior app.
            session.removeAttribute(AppStepUpFilter.RETURN);
            session.removeAttribute(AppStepUpFilter.APP_TYPE);
            session.removeAttribute(AppStepUpFilter.APP_ID);
        }
        return new StepUpInfo(access.ready(), access.pendingFactors(), returnUrl);
    }

    private AppUser requireUser(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private static String attr(HttpSession session, String name, String fallback) {
        if (session == null) {
            return fallback;
        }
        Object value = session.getAttribute(name);
        return value instanceof String s ? s : fallback;
    }
}
