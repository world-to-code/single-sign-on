package com.example.sso.admin.internal.user.application;

import java.util.List;

/**
 * A permission the user holds, together with the roles that actually confer it.
 *
 * <p>The roles are the actionable half. A permission listed alone tells an administrator what somebody can
 * do; the conferring role tells them what to change to stop it — and it may be a role the user does not
 * hold, because a role they DO hold inherits it. Revoking the visible one then leaves the permission behind.
 *
 * @param permission  the held permission (or a granted wildcard token)
 * @param conferredBy the names of the roles carrying it, empty for a direct grant or a wildcard token that
 *                    no single role accounts for
 * @param viaGroups   the groups delegating a conferring role, empty when every one is held directly. A role
 *                    that arrives this way is removed from the GROUP; taking it off the user does nothing.
 */
public record HeldPermissionView(String permission, List<String> conferredBy, List<String> viaGroups) {

    public HeldPermissionView {
        conferredBy = List.copyOf(conferredBy);
        viaGroups = List.copyOf(viaGroups);
    }
}
