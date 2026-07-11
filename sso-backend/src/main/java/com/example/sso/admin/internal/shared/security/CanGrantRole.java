package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for granting a role to a user from the role's member list: the caller needs
 * {@code user:update}, must be able to reach the target under scope, and — unless a super admin — the
 * role must be non-privileged and the target not already an administrator (see
 * {@code AdminAccessPolicy#canGrantRole}). Applies to a method with a path variable {@code id} (the
 * role) and {@code userId} (the target user).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
        + "') and @adminAccessPolicy.canGrantRole(#userId, #id)")
public @interface CanGrantRole {
}
