package com.example.sso.portal.access;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.stepup.AppStepUpFilter;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Assignment gate for {@code /oauth2/authorize}: a client flagged with
 * {@link AdminPortalSeeder#REQUIRES_ASSIGNMENT_SETTING} (the admin console) may only be authorized by
 * a user the app is ASSIGNED to (directly or via a role/group) — entry is an assignment, not a role.
 * An unassigned user is denied per RFC 6749 ({@code error=access_denied}) via the requested
 * {@code redirect_uri} ONLY when it exactly matches a registered one (no open redirect); otherwise 403.
 * Runs before {@link AppStepUpFilter}: no point acquiring step-up factors for an app you cannot enter.
 *
 * <p>NOT a {@code @Component}: it must sit INSIDE the authorization-server {@code SecurityFilterChain}
 * that serves {@code /oauth2/authorize} (the endpoint filter commits the response and never reaches the
 * outer servlet chain), so {@code AuthorizationServerConfig} wires it in via {@code addFilterBefore}.
 */
@RequiredArgsConstructor
public class AppAssignmentFilter extends OncePerRequestFilter {

    private final RegisteredClientRepository registeredClients;
    private final ApplicationService applications;
    private final UserService users;
    private final AuditService audit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // servletPath (app-relative, normalized) — NOT getRequestURI(), which includes the context path
        // and would silently disable this gate if one were ever configured.
        return !"/oauth2/authorize".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (!isFullyAuthenticated(auth) || clientId == null) {
            chain.doFilter(request, response); // unauthenticated → the normal login redirect handles it
            return;
        }

        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null
                || !Boolean.TRUE.equals(client.getClientSettings()
                        .getSetting(AdminPortalSeeder.REQUIRES_ASSIGNMENT_SETTING))) {
            chain.doFilter(request, response); // unknown client → auth server rejects; unflagged → open
            return;
        }

        UserAccount user = users.findByUsername(auth.getName()).orElse(null);
        if (user != null && applications.hasAssignment(user, AppType.OIDC, client.getId())) {
            chain.doFilter(request, response);
            return;
        }

        audit.record(new AuditRecord(AuditType.OIDC_APP_ACCESS, auth.getName(), false,
                "client=" + client.getId() + ", denied=not_assigned", request.getRemoteAddr()));
        deny(request, response, client);
    }

    /** RFC 6749 §4.1.2.1 denial to an EXACTLY registered redirect_uri; anything else gets a plain 403. */
    private void deny(HttpServletRequest request, HttpServletResponse response, RegisteredClient client)
            throws IOException {
        String redirectUri = request.getParameter(OAuth2ParameterNames.REDIRECT_URI);
        if (redirectUri == null || !client.getRedirectUris().contains(redirectUri)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"access_denied\",\"error_description\":\"Application not assigned.\"}");
            return;
        }

        UriComponentsBuilder location = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam(OAuth2ParameterNames.ERROR, "access_denied")
                .queryParam(OAuth2ParameterNames.ERROR_DESCRIPTION,
                        UriUtils.encodeQueryParam("Application not assigned.", StandardCharsets.UTF_8));
        String state = request.getParameter(OAuth2ParameterNames.STATE);
        if (state != null) {
            location.queryParam(OAuth2ParameterNames.STATE, UriUtils.encodeQueryParam(state, StandardCharsets.UTF_8));
        }
        response.sendRedirect(location.build(true).toUriString());
    }

    private boolean isFullyAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .anyMatch(Factors.MFA_COMPLETE::equals);
    }
}
