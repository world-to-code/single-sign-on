package com.example.sso.user.internal.application;

import com.example.sso.user.internal.domain.RoleHierarchyEdge;
import com.example.sso.user.internal.domain.RoleHierarchyEdgeId;
import com.example.sso.user.internal.domain.RoleHierarchyEdgeRepository;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single owner of WRITES to the role-inheritance DAG — the write-side companion to {@link RoleClosure}'s
 * reads. Both seeding ({@code RbacServiceImpl}) and the admin role-builder ({@code RoleServiceImpl}) mutate
 * the graph through here, so its invariants — idempotent insert, cycle refusal, RLS-scoped flush, and
 * explicit teardown on role deletion — live in ONE place and cannot drift between the two call paths.
 */
@Component
@RequiredArgsConstructor
class RoleHierarchyWriter {

    private final RoleHierarchyEdgeRepository edges;
    private final RoleClosure roleClosure;

    /**
     * Inserts a {@code parent → child} edge idempotently, refusing one that would close a cycle. Uses
     * {@code saveAndFlush} so the INSERT runs WHILE the org/platform scope is still bound: a plain
     * {@code save} would defer the write to commit — after {@code callInOrg}/{@code callAsPlatform} restored
     * the outer context — and the RLS {@code WITH CHECK} would then reject the row.
     */
    void link(UUID parentRoleId, UUID childRoleId, UUID orgId) {
        if (edges.existsById(new RoleHierarchyEdgeId(parentRoleId, childRoleId))) {
            return;
        }
        if (roleClosure.descendantsAndSelf(Set.of(childRoleId)).contains(parentRoleId)) {
            throw new IllegalStateException("role-hierarchy edge would create a cycle");
        }
        edges.saveAndFlush(new RoleHierarchyEdge(parentRoleId, childRoleId, orgId));
    }

    /**
     * Removes every edge into OR out of {@code roleId} — the explicit teardown run when a role is deleted, so
     * the code (not just the {@code ON DELETE CASCADE} FK) documents that the role's inheritance edges go too.
     */
    void unlinkRole(UUID roleId) {
        edges.deleteByRoleId(roleId);
    }
}
