package com.example.sso.admin;

import com.example.sso.admin.internal.portalsettings.application.AdminPortalSettingsRequest;

/**
 * Reads and updates the single-row admin-portal security settings (admin-console re-auth/elevation
 * and session-timeout tunables), exposed as the public {@link AdminPortalSettingsData} projection.
 * The backing entity and the implementation stay module-internal.
 */
public interface AdminPortalSettingsService {

    AdminPortalSettingsData get();

    AdminPortalSettingsData update(AdminPortalSettingsRequest request);
}
