package com.example.sso.user.internal.role.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to the {@code role_hierarchy} DAG (role→role inheritance edges). */
public interface RoleHierarchyEdgeRepository extends JpaRepository<RoleHierarchyEdge, RoleHierarchyEdgeId> {

    /** Removes every edge into OR out of the role — explicit teardown when the role is deleted. */
    @Modifying
    @Query("delete from RoleHierarchyEdge e where e.id.parentRoleId = :roleId or e.id.childRoleId = :roleId")
    void deleteByRoleId(@Param("roleId") UUID roleId);

    /**
     * The DIRECT children of a role (the roles it inherits directly), NOT the transitive closure — the ids
     * only, never the entity. RLS-confined like every read here (a tenant sees its own + global edges).
     */
    @Query("select e.id.childRoleId from RoleHierarchyEdge e where e.id.parentRoleId = :parentRoleId")
    List<UUID> findChildRoleIdsByParentRoleId(@Param("parentRoleId") UUID parentRoleId);

    /**
     * Removes a single {@code parent → child} edge (bulk delete, runs in-scope so RLS gates the write). No
     * {@code clearAutomatically} is needed because every read after a delete in the same tx is a scalar/native
     * projection (child-id lists, the recursive CTEs) — never an entity re-fetch that could see a stale cached
     * edge; add it if an entity-returning finder on {@code role_hierarchy} is ever read post-delete in one tx.
     */
    @Modifying
    @Query("delete from RoleHierarchyEdge e where e.id.parentRoleId = :parentRoleId and e.id.childRoleId = :childRoleId")
    void deleteEdge(@Param("parentRoleId") UUID parentRoleId, @Param("childRoleId") UUID childRoleId);

    /**
     * The transitive descendant closure of the given root roles (strict — the roots themselves are NOT
     * included). One recursive CTE, {@code UNION}-deduped so DAG diamonds terminate and any corrupt cycle
     * cannot loop. Native so RLS on {@code role_hierarchy} applies: a tenant context sees only its own +
     * global edges, so the walk stays within the caller's tier.
     */
    @Query(value = """
            WITH RECURSIVE descendants AS (
                SELECT child_role_id FROM role_hierarchy WHERE parent_role_id IN (:rootIds)
                UNION
                SELECT rh.child_role_id
                FROM role_hierarchy rh
                JOIN descendants d ON rh.parent_role_id = d.child_role_id
            )
            SELECT child_role_id FROM descendants
            """, nativeQuery = true)
    List<UUID> descendantRoleIds(@Param("rootIds") Collection<UUID> rootIds);

    /**
     * The transitive ancestor closure of the given roles (strict — the roles themselves are NOT included):
     * every role that inherits (directly or transitively) from one of them. Backs the "hide roles above the
     * actor" list filter. Same RLS-confined recursive-CTE shape as {@link #descendantRoleIds}.
     */
    @Query(value = """
            WITH RECURSIVE ancestors AS (
                SELECT parent_role_id FROM role_hierarchy WHERE child_role_id IN (:roleIds)
                UNION
                SELECT rh.parent_role_id
                FROM role_hierarchy rh
                JOIN ancestors a ON rh.child_role_id = a.parent_role_id
            )
            SELECT parent_role_id FROM ancestors
            """, nativeQuery = true)
    List<UUID> ancestorRoleIds(@Param("roleIds") Collection<UUID> roleIds);
}
