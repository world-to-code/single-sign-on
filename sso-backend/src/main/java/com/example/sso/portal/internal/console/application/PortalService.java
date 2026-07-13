package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.access.AppAssignmentFilter;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.access.AppAccessQuery;
import com.example.sso.portal.stepup.AppStepUpFilter;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

/**
 * End-user portal logic: the applications the signed-in user may launch, the SPA's session timers, and
 * the /stepup page state (re-evaluating the pending app launch and clearing it once satisfied). Keeps
 * the controller a thin adapter.
 */
@Service
@RequiredArgsConstructor
public class PortalService {

    private static final String DEFAULT_RETURN = "/";

    private final ApplicationService applications;
    private final UserService users;
    private final SessionPolicyService sessionPolicy;
    private final PolicyBindingResolver bindings;
    private final OrgContext orgContext;
    private final RegisteredClientRepository registeredClients;

    public SessionConfigView sessionConfig(String username) {
        SessionPolicyDetails p = resolveSessionPolicy(username);
        List<String> reauthFactors = Arrays.stream(p.getReauthFactors().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new SessionConfigView(p.getIdleTimeoutMinutes(), p.getReauthIntervalMinutes(), reauthFactors);
    }

    /**
     * The session policy governing the END-USER PORTAL for this user: the {@code PORTAL}/{@code user} binding
     * (resolved in the acting tenant's context, most-specific first), else the user's own resolved policy. This
     * is the user-portal twin of {@code AdminConsolePolicy}; scoping to the acting org (never the ambient
     * platform context) keeps a tenant's binding from leaking across tenants under RLS.
     */
    private SessionPolicyDetails resolveSessionPolicy(String username) {
        return users.findByUsername(username)
                .flatMap(user -> orgContext.callInOrg(orgContext.currentOrg().orElse(null),
                        () -> bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)))
                .orElseGet(() -> sessionPolicy.resolveForUsername(username));
    }

    public List<ApplicationView> myApps(String username) {
        return applications.appsForUser(requireUser(username));
    }

    /**
     * Whether the user may ENTER the admin console — an app assignment (direct/role/group), matching
     * what {@code AppAssignmentFilter} enforces at {@code /oauth2/authorize}. The SPA gates its
     * "Administration" affordance on this instead of a role.
     */
    public AdminConsoleAccessView adminConsoleAccess(String username) {
        RegisteredClient console = registeredClients.findByClientId(AdminPortalSeeder.CLIENT_ID);
        boolean allowed = console != null
                && applications.hasAssignment(requireUser(username), AppType.OIDC, console.getId());
        return new AdminConsoleAccessView(allowed);
    }

    /**
     * Re-evaluates the pending app launch (stored in the session by the step-up filter / SAML endpoint)
     * and reports whether it is ready or which factor is still needed; clears the pending launch once ready.
     */
    public StepUpInfo stepup(Authentication authentication, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String returnUrl = attr(session, AppStepUpFilter.RETURN, DEFAULT_RETURN);
        String type = attr(session, AppStepUpFilter.APP_TYPE, null);
        String appId = attr(session, AppStepUpFilter.APP_ID, null);
        if (type == null || appId == null) {
            return new StepUpInfo(true, List.of(), returnUrl);
        }

        Set<String> granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith(Factors.FACTOR_PREFIX))
                .collect(Collectors.toSet());
        AppType appType = AppType.valueOf(type);
        AppAccess access = applications.appAccess(new AppAccessQuery(requireUser(authentication.getName()), appType,
                appId, granted, AppStepUpFilter.lastAppStepUp(session, appType, appId)));
        if (access.ready()) {
            // Clear the pending step-up so a later /stepup visit doesn't auto-redirect into a prior app.
            session.removeAttribute(AppStepUpFilter.RETURN);
            session.removeAttribute(AppStepUpFilter.APP_TYPE);
            session.removeAttribute(AppStepUpFilter.APP_ID);
        }

        return new StepUpInfo(access.ready(), access.pendingFactors(), returnUrl);
    }

    private UserAccount requireUser(String username) {
        return users.findByUsername(username).orElseThrow(UnauthorizedException::new);
    }

    private String attr(HttpSession session, String name, String fallback) {
        if (session == null) {
            return fallback;
        }
        Object value = session.getAttribute(name);
        return value instanceof String s ? s : fallback;
    }
}
