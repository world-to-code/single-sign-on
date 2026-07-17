package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for admin force-expiry of a GROUP's members' sessions: the caller needs {@code user:update}
 * (the same authority the single-user force-expiry requires) and must be allowed to reach the group
 * ({@code #id}) under subtree scope ({@code canAccessGroup}). The per-member reach ({@code canAccessUser} —
 * a scoped delegate must not reach a member outside its subtree) and revoke-eligibility ({@code canRevokeSessions}
 * — nor force-logout an administrator) guards are applied INSIDE {@code GroupAdminService.terminateMemberSessions}
 * (it skips members it may not revoke), not here, because a group may mix members of differing eligibility.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessGroup(#id)")
public @interface CanRevokeGroupSessions {
}
