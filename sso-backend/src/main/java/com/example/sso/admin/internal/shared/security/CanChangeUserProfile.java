package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for writing a user's profile — the columns they hold, or the profile that declares them: the
 * caller needs {@code user:update}, must be allowed to reach the target ({@code #id}) under group scope, and
 * must not be a non-super aiming at another administrator.
 *
 * <p>Deliberately NOT {@link CanUpdateUser}: that gate also judges the enabled flag and the role set of an
 * account edit, reading both off {@code #request}. A profile write carries neither, so pointing it here does
 * not merely over-ask — the expression cannot be evaluated at all, and the endpoint then fails for the
 * administrator who was ALLOWED rather than the one who was not.
 *
 * <p>What that gate DID assert still has to hold, though, which is what the third conjunct is for. A profile
 * write deletes attributes; an attribute can be the condition on a mapping rule or a policy binding; so the
 * write retracts roles and ends sessions. Splitting the gate on the request's SHAPE and dropping the policy
 * with it left an administrator reachable here who is refused at every sibling route on the same permission.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)"
        + " and @adminAccessPolicy.canChangeProfile(#id)")
public @interface CanChangeUserProfile {
}
