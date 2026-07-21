package com.example.sso.shared.security;

import com.example.sso.user.rbac.Permissions;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Bulk user creation from a file: the permission AND the scope, exactly as {@code CanCreateUser} demands them
 * for one user at a time.
 *
 * <p>The permission alone was not enough and the gap was real. A resource-subtree delegate holding
 * {@code user:create} is refused by {@code POST /api/admin/users} because {@code canCreateUser()} is false for
 * anyone who is neither a super admin nor the bound organization's administrator — but the import route
 * checked only the authority, so the same actor could mint accounts across the entire tenant, five thousand at
 * a time, through the one route that skipped the ABAC half. Instance-level checks compose with {@code and};
 * a route that drops one is a route that grants what the other denies.
 *
 * <p>Lives beside {@link RequirePermission} rather than with the admin module's own gate annotations because
 * the route it guards is served from another module. The SpEL names {@code adminAccessPolicy} as a BEAN, so
 * this is a runtime lookup and no module dependency is created by it.
 *
 * <p>No {@code mayAssignRoles} term HERE, because the roles an import can confer are not in the request: a
 * file names no role, but it names GROUPS, and a group delegates its roles to every member. The ceiling
 * therefore lives where the group set is known — {@code CsvGroupDirectoryAdapter} treats a group whose
 * delegated roles the actor may not assign as unusable, so the row is refused exactly as an unreachable group
 * is. Saying "an imported account is created with no roles, so there is nothing to assign" — as this did —
 * was how the membership grant became a way around the direct-grant ceiling.
 *
 * <p>If a file ever names roles directly, this annotation is where THAT check belongs.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "') and @adminAccessPolicy.canCreateUser()")
public @interface CanImportUsers {
}
