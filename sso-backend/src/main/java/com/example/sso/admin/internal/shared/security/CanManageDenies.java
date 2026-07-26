package com.example.sso.admin.internal.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Coarse URL gate for authoring/lifting a deny: the caller manages user or role authority. The AUTHORITATIVE,
 * per-subject decision (may this actor withhold THIS pattern from THIS subject / lift THIS stored deny) lives in
 * {@code DenyService} via the {@code DenyAuthority} port — a grant-symmetric, dominance-gated, LIVE check that
 * loads the stored row for a lift. This annotation is the mutating-permission floor the URL must carry
 * (OWASP A01); it is deliberately not the whole check.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') or hasAuthority('" + Permissions.ROLE_UPDATE + "')")
public @interface CanManageDenies {
}
