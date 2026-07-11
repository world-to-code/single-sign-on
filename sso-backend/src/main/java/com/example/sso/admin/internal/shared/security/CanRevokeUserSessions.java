package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for admin force-expiry of a user's sessions: the caller needs {@code user:update}, must be
 * allowed to reach the target ({@code #id}) under group scope, and must satisfy {@code canRevokeSessions}
 * (a super admin may revoke anyone — force-expiring a compromised admin is legitimate — but a scoped
 * delegate may not target another administrator). No {@code #body}/{@code #request} clause, so it fits an
 * endpoint whose only parameter is {@code id}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)"
        + " and @adminAccessPolicy.canRevokeSessions(#id)")
public @interface CanRevokeUserSessions {
}
