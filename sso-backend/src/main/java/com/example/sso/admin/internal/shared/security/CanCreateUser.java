package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for creating a user: the caller needs {@code user:create} and must be able to create a user
 * in the acting scope (a super admin anywhere; a tenant admin within their own org). The roles requested at
 * creation are gated by {@code mayAssignRoles}, so a non-super may never mint an administrator or a user
 * bearing a privileged/platform-permission role (escalation).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "') and @adminAccessPolicy.canCreateUser()"
        + " and @adminAccessPolicy.mayAssignRoles(#request.roles())")
public @interface CanCreateUser {
}
