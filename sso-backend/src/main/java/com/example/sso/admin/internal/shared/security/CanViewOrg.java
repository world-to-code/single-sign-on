package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC to read one organization: the caller needs {@code organization:read} and must be allowed to
 * reach the target org ({@code #id}) — a super admin may read any org; a scoped org-admin only their own.
 * Applies to a method with an {@code id} path variable.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.ORG_READ + "') and @adminAccessPolicy.canAccessOrg(#id)")
public @interface CanViewOrg {
}
