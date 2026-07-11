package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for a profile/roles update: the caller needs {@code user:update}, must be allowed to
 * reach the target ({@code #id}) under group scope, and must satisfy the self/other-admin protection
 * rules for the requested enabled-flag and role set. Applies to a method with parameters named
 * {@code id} and {@code request} (the latter exposing {@code enabled()} and {@code roles()}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
        + "') and @adminAccessPolicy.canAccessUser(#id)"
        + " and @adminAccessPolicy.canUpdateUser(#id, #request.enabled(), #request.roles())")
public @interface CanUpdateUser {
}
