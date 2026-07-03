package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for revoking a role from a user via the role's member list: same rules as
 * {@link CanGrantRole}, plus an admin may not revoke their own {@code ROLE_ADMIN} (self-demotion — see
 * {@code AdminAccessPolicy#canRevokeRole}). The last-administrator invariant is a separate 409 in the
 * service. Applies to a method with a path variable {@code id} (the role) and {@code userId} (the user).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
        + "') and @adminAccessPolicy.canRevokeRole(#userId, #id)")
public @interface CanRevokeRole {
}
