package com.example.sso.shared.security;

import com.example.sso.user.rbac.Permissions;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Method-level PBAC: the caller must hold the given fine-grained permission (authority).
 *
 * <p>A readable, compile-time-checked alternative to
 * {@code @PreAuthorize("hasAuthority('" + Permissions.X + "')")}: the value is a
 * {@code com.example.sso.user.rbac.Permissions} constant, so a renamed/removed permission fails the build
 * and IDE navigation works. The {@code {value}} placeholder is substituted by Spring Security's
 * meta-annotation templating (enabled by the {@code AnnotationTemplateExpressionDefaults} bean in
 * {@code MethodSecurityConfig}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('{value}')")
public @interface RequirePermission {

    /** The required permission authority, e.g. {@code Permissions.USER_READ}. */
    String value();
}
