package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for setting a user's direct permissions: the caller needs {@code user:update}, must be
 * allowed to reach the target ({@code #id}) under group scope, and must be a super admin who is not
 * editing another administrator (a scoped admin is blocked outright to prevent self-escalation).
 * Applies to a method whose target-user path variable is named {@code id}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
        + "') and @adminAccessPolicy.canAccessUser(#id) and @adminAccessPolicy.canManagePermissions(#id)")
public @interface CanManageUserPermissions {
}
