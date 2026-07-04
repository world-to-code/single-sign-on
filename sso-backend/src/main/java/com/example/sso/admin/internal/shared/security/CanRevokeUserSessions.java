package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for admin force-expiry of a user's sessions: the caller needs {@code user:update} and must
 * be allowed to reach the target ({@code #id}) under group scope. Unlike {@link CanSetUserEnabled}/
 * {@link CanUpdateUser} it carries no self/other-admin guard — force-expiring a compromised admin's
 * sessions is a legitimate response — and no {@code #body}/{@code #request} clause, so it fits an
 * endpoint whose only parameter is {@code id}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)")
public @interface CanRevokeUserSessions {
}
