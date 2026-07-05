package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.PermissionGrantPolicy;
import com.example.sso.user.Permissions;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Admin-module implementation of {@link PermissionGrantPolicy}: a platform-only permission
 * ({@link Permissions#isPlatform}) may be granted only by a platform super-admin (unscoped
 * {@code ROLE_ADMIN}); every other (tenant-grantable) permission is allowed. Fails closed — with no
 * resolvable super actor in context, a platform permission is refused (deny-by-default). This is the
 * authoritative write-path control behind the role builder; the catalog GET filter is only the UI side.
 *
 * <p>{@link AdminAccessPolicy} is injected {@code @Lazy} to break the construction cycle
 * {@code RoleServiceImpl → PermissionGrantPolicy → AdminAccessPolicy → RoleService}: the policy is only
 * consulted at role-write time, long after the context is built.
 */
@Component
public class AdminPermissionGrantPolicy implements PermissionGrantPolicy {

    private final AdminAccessPolicy accessPolicy;

    public AdminPermissionGrantPolicy(@Lazy AdminAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    @Override
    public boolean mayGrant(String permission) {
        return !Permissions.isPlatform(permission) || accessPolicy.isCurrentActorUnscoped();
    }
}
