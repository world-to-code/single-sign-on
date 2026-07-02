package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for delegating roles to a group: the caller needs {@code group:update} and, unless a
 * super admin, may not assign a privileged role (ROLE_ADMIN/ROLE_GROUP_ADMIN) to a group. Applies to
 * a method with a parameter named {@code request} exposing {@code roleNames()}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE
        + "') and @adminAccessPolicy.mayAssignRoles(#request.roleNames())")
public @interface CanAssignGroupRoles {
}
