package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.AdminPortalSettingsData;

/** Read model for the admin console's governing session policy (null = the acting admin's own policy). */
public record AdminPortalSettingsView(String sessionPolicyId) {

    static AdminPortalSettingsView of(AdminPortalSettingsData settings) {
        return new AdminPortalSettingsView(settings.sessionPolicyId() == null
                ? null : settings.sessionPolicyId().toString());
    }
}
