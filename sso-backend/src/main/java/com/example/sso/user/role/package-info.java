/**
 * Named interface for roles and the role-inheritance DAG: the {@link RoleService} facade, the
 * {@link RoleHierarchyService} dominance/apex queries, the {@link RoleRef} read model and the {@code Roles}
 * name constants. The role graph and its persistence stay module-internal.
 */
@NamedInterface("role")
package com.example.sso.user.role;

import org.springframework.modulith.NamedInterface;
