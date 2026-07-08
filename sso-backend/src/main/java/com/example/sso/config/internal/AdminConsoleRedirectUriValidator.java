package com.example.sso.config.internal;

import com.example.sso.oidc.AdminPortalSeeder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code redirect_uri} validation for {@code /oauth2/authorize} that lets the first-party admin console be
 * entered from EVERY tenant subdomain without pre-registering each one. For the {@code admin-console} client
 * it accepts a redirect_uri that is EXACTLY the current request's own origin + {@code /admin/callback} (the
 * SPA always calls back to its own origin); everything else falls through to the framework default (scope +
 * registered-redirect check).
 *
 * <p>Safe because the accepted target is pinned to the SAME host the authorize request arrived on — a host
 * {@code TenantHostFilter} has already resolved to a real tenant (an unknown subdomain is a 404). A code can
 * therefore never be redirected to another origin or another tenant: no open redirect, no cross-tenant code
 * interception. Only the first-party public console client (PKCE, no secret) is exempted; tenant/confidential
 * clients keep the strict registered-set check.
 */
final class AdminConsoleRedirectUriValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    private static final String ADMIN_CALLBACK_PATH = "/admin/callback";

    private final Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> defaultValidator =
            new OAuth2AuthorizationCodeRequestAuthenticationValidator();

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        RegisteredClient client = context.getRegisteredClient();
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        if (AdminPortalSeeder.CLIENT_ID.equals(client.getClientId())
                && isSameOriginAdminCallback(currentRequest(), authentication.getRedirectUri())) {
            // Same-origin admin console callback: skip the registered-set redirect check (tenant subdomains are
            // not enumerable), but STILL enforce the requested scopes via the framework's default validator.
            OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
            return;
        }
        defaultValidator.accept(context);
    }

    /**
     * True when {@code redirectUri} is EXACTLY the request's own origin + {@code /admin/callback} — same scheme,
     * same host:port (the SPA sends {@code window.location.origin + "/admin/callback"}), exact path. Anything
     * pointing at a different origin/host or path is rejected, so a code can only ever return to the host the
     * authorize request came in on.
     */
    static boolean isSameOriginAdminCallback(HttpServletRequest request, String redirectUri) {
        if (request == null || redirectUri == null) {
            return false;
        }
        String host = request.getHeader("Host"); // includes the port — matches window.location.origin
        if (host == null || host.isBlank()) {
            return false;
        }
        return (request.getScheme() + "://" + host + ADMIN_CALLBACK_PATH).equals(redirectUri);
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
