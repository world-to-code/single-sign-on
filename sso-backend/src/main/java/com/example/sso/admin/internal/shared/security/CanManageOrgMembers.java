package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC to manage an organization's membership: the caller needs {@code organization:member-manage}
 * and must be allowed to reach the target org ({@code #id}) — a super admin may manage any org's members;
 * a scoped org-admin only their own org's. Applies to a method with an {@code id} path variable.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.ORG_MEMBER_MANAGE + "') and @adminAccessPolicy.canAccessOrg(#id)")
public @interface CanManageOrgMembers {
}
