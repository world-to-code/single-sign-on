package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for setting a user's direct permissions: the caller needs {@code user:update}, must be
 * allowed to reach the target ({@code #id}), must be a super admin or a tenant admin acting in their own
 * org (and never editing an administrator), and may hand out only permissions that are tenant-grantable
 * and that they themselves hold. Applies to a method whose target-user path variable is named {@code id}
 * and whose request body ({@code body}) exposes {@code permissions()}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
        + "') and @adminAccessPolicy.canAccessUser(#id) and @adminAccessPolicy.canManagePermissions(#id)"
        + " and @adminAccessPolicy.mayGrantPermissions(#body.permissions())")
public @interface CanManageUserPermissions {
}
