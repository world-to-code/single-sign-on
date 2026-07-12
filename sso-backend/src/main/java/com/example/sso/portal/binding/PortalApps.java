package com.example.sso.portal.binding;

/**
 * The {@code app_id} of each IdP-served portal under {@link com.example.sso.portal.application.AppType#PORTAL}.
 * These are the policy-binding targets for the admin console and the end-user portal.
 */
public final class PortalApps {

    public static final String ADMIN = "admin";
    public static final String USER = "user";

    private PortalApps() {
    }
}
