package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationDescriptor;
import com.example.sso.portal.application.ApplicationSource;
import com.example.sso.portal.binding.PortalApps;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Publishes the IdP-served end-user portal as a first-class application, so it appears in the admin
 * catalog and can carry its own session policy through a {@code PORTAL}/{@code user} binding. Unlike the
 * OIDC/SAML sources this is tier-INDEPENDENT: every tenant shares the same one user portal, so the
 * descriptor is global and appears in every tier's catalog (like the admin console tile).
 *
 * <p>The admin console is intentionally NOT published here — it stays an OIDC application
 * ({@code OidcApplicationSource}) because its runtime entry is an OAuth2 authorize flow gated by an app
 * assignment; only its session policy already lives in the binding matrix.
 */
@Component
public class PortalApplicationSource implements ApplicationSource {

    static final String USER_PORTAL_NAME = "User Portal";
    private static final String USER_PORTAL_LAUNCH_URL = "/";

    @Override
    public List<ApplicationDescriptor> applications() {
        return List.of(new ApplicationDescriptor(AppType.PORTAL, PortalApps.USER, USER_PORTAL_NAME,
                USER_PORTAL_LAUNCH_URL, true));
    }
}
