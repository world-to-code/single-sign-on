package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * PBAC + ABAC for creating a user: the caller needs {@code user:create} and must be a super admin
 * (a scoped admin manages existing members only).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "') and @adminAccessPolicy.canCreateUser()")
public @interface CanCreateUser {
}
