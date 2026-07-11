package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for delegating roles to a group: the caller needs {@code group:update}, must be able to access
 * the target group (super admin, resource delegate over it, or a tenant admin of its org), and, unless a super
 * admin, may delegate only roles that sit strictly BELOW them in the inheritance DAG and whose permissions they
 * themselves hold ({@code mayAssignRoles} — the same dominance gate as a direct user grant, so a role at/above
 * the actor's level can never be delegated through a group either). Applies to a method with a {@code UUID id}
 * path variable (the group) and a {@code request} parameter exposing {@code roleNames()}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE
        + "') and @adminAccessPolicy.canAccessGroup(#id) and @adminAccessPolicy.mayAssignRoles(#request.roleNames())")
public @interface CanAssignGroupRoles {
}
