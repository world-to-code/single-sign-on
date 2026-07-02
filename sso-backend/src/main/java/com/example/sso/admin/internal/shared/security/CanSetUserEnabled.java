package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for toggling a user's enabled state: the caller needs {@code user:update}, must be
 * allowed to reach the target ({@code #id}) under group scope, and may not disable their own or
 * another administrator's account. Applies to a method with parameters named {@code id} and
 * {@code body} (the latter exposing {@code enabled()}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
        + "') and @adminAccessPolicy.canAccessUser(#id)"
        + " and @adminAccessPolicy.canSetEnabled(#id, #body.enabled())")
public @interface CanSetUserEnabled {
}
