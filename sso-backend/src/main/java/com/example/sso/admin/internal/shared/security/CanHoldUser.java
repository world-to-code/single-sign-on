package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for placing and lifting an account hold: the caller needs {@code user:update}, must be allowed
 * to reach the target ({@code #id}) under group scope, and must satisfy {@code canHoldUser} (not themselves,
 * and not another administrator unless they are a super admin).
 *
 * <p>The SAME annotation guards both directions on purpose. Lifting a hold loosens the account's posture, so
 * a lighter guard there would be a way to undo a detection's response while answering only for the ability to
 * cause one — the shape the guard-direction rule exists to prevent.
 *
 * <p>No {@code #body}/{@code #request} clause, so it fits both the PUT (which carries one) and the DELETE
 * (which does not).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)"
        + " and @adminAccessPolicy.canHoldUser(#id)")
public @interface CanHoldUser {
}
