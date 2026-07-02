package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for reading a single user (or a section of the user detail page): the caller needs
 * {@code user:read} and must be allowed to reach the target ({@code #id}) under group scope. Applies
 * to a controller method whose target-user path variable is named {@code id}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_READ + "') and @adminAccessPolicy.canAccessUser(#id)")
public @interface CanViewUser {
}
