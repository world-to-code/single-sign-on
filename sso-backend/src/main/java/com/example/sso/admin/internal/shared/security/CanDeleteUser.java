package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for deleting a user: the caller needs {@code user:delete}, must be allowed to reach the
 * target ({@code #id}) under group scope, and may not delete their own or another administrator's
 * account. Applies to a method whose target-user path variable is named {@code id}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_DELETE
        + "') and @adminAccessPolicy.canAccessUser(#id) and @adminAccessPolicy.canDeleteUser(#id)")
public @interface CanDeleteUser {
}
