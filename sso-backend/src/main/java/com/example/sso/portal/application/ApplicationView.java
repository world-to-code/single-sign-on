package com.example.sso.portal.application;

/**
 * A launchable application surfaced in the user portal / admin. {@code requiredPolicyId}/Name is the
 * app-level sign-on policy (applies to everyone accessing the app); null when none is set.
 *
 * <p>{@code system} marks a platform-managed app (the first-party admin console): it cannot be
 * edited/deleted, launches the admin SPA ({@code /admin}), and is auto-granted to admins.
 */
public record ApplicationView(String id, String type, String name, String launchUrl, boolean system,
                              String requiredPolicyId, String requiredPolicyName) {

    /** Projects a catalog descriptor, given its resolved app-level policy id/name (both null when none). */
    public static ApplicationView of(ApplicationDescriptor app, String requiredPolicyId, String requiredPolicyName) {
        return new ApplicationView(app.id(), app.type().name(), app.name(), app.launchUrl(), app.system(),
                requiredPolicyId, requiredPolicyName);
    }
}
