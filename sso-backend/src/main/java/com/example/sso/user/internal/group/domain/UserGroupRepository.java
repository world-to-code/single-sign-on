package com.example.sso.user.internal.group.domain;

import com.example.sso.shared.IdName;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    /** The GLOBAL/system group with this name (org_id IS NULL) — name lookup resolves to the global tier;
     *  tenant groups are addressed by id. Deterministic via the {@code uq_user_group_name_global} index. */
    Optional<UserGroup> findByNameAndOrgIdIsNull(String name);

    /** A group with this name within the given org — the per-tenant uniqueness check on creation. */
    Optional<UserGroup> findByNameAndOrgId(String name, UUID orgId);

    Optional<UserGroup> findByExternalId(String externalId);

    List<UserGroup> findAllByOrderByNameAsc();

    /**
     * A page of groups, ordered by name. No collection fetch-join (the member-ids and roles are
     * batch-loaded per page), so the {@code Pageable} paginates in SQL rather than in memory.
     */
    Page<UserGroup> findByOrderByNameAsc(Pageable pageable);

    /** A scoped page: groups whose id is in {@code ids} (a delegate's subtree), ordered by name. */
    Page<UserGroup> findByIdInOrderByNameAsc(Collection<UUID> ids, Pageable pageable);

    /** A page of ONE organization's groups (excludes GLOBAL/system groups) — a tenant admin's directory,
     *  which must never surface the platform-wide groups RLS keeps visible for login role-resolution. */
    Page<UserGroup> findByOrgIdOrderByNameAsc(UUID orgId, Pageable pageable);

    /** The named groups of one organization, in one query — for a bulk operation that resolves many at once. */
    List<UserGroup> findByOrgIdAndNameIn(UUID orgId, Collection<String> names);

    /** A page of the GLOBAL/system groups (org_id IS NULL) — what an un-drilled platform admin sees. */
    Page<UserGroup> findByOrgIdIsNullOrderByNameAsc(Pageable pageable);

    Optional<UserGroup> findFirstBySystemTrue();

    /**
     * Groups the given user is a member of. Joined from {@code UserGroup} (not the raw join table) so the
     * result is tenant-scoped by RLS: groups not visible in the current context are excluded.
     */
    @Query("select g from UserGroup g where g.id in "
            + "(select m.id.groupId from UserGroupMember m where m.id.userId = :userId)")
    List<UserGroup> findByMember(@Param("userId") UUID userId);

    /** Ids of the (visible) groups the given user belongs to. */
    @Query("select g.id from UserGroup g where g.id in "
            + "(select m.id.groupId from UserGroupMember m where m.id.userId = :userId)")
    List<UUID> findGroupIdsByMember(@Param("userId") UUID userId);

    /** Distinct ids of all users who are members of ANY of the given (visible) groups (bulk scope expansion). */
    @Query("select distinct mem.id.userId from UserGroup g "
            + "join UserGroupMember mem on mem.id.groupId = g.id where g.id in :groupIds")
    List<UUID> findMemberIdsByGroupIds(@Param("groupIds") Collection<UUID> groupIds);

    /** Distinct ids of all users who hold the given role via ANY group that delegates it (group-delegated
     *  holders) — used to end their sessions when the role's permissions or existence change. */
    @Query("select distinct mem.id.userId from UserGroup g "
            + "join UserGroupRole gr on gr.id.groupId = g.id "
            + "join UserGroupMember mem on mem.id.groupId = g.id where gr.id.roleId = :roleId")
    List<UUID> findMemberIdsByRoleId(@Param("roleId") UUID roleId);

    /**
     * Distinct ids of the roles delegated to the user via any (visible) group they belong to — used when
     * building the member's effective authorities at login. The role entities (and their permission names)
     * are then loaded/hydrated explicitly by the caller.
     */
    @Query("select distinct gr.id.roleId from UserGroup g "
            + "join UserGroupRole gr on gr.id.groupId = g.id where g.id in "
            + "(select m.id.groupId from UserGroupMember m where m.id.userId = :userId)")
    List<UUID> findDelegatedRoleIdsForMember(@Param("userId") UUID userId);

    /** (id, name) for the given groups — batch name lookup without loading membership. */
    @Query("select g.id as id, g.name as name from UserGroup g where g.id in :ids")
    List<IdName> findIdNames(Collection<UUID> ids);

    /** Typeahead search by name (case-insensitive). */
    @Query("select g.id as id, g.name as name from UserGroup g "
            + "where lower(g.name) like lower(concat('%', :q, '%')) order by g.name asc")
    List<IdName> search(@Param("q") String q, Pageable limit);

    /** Typeahead search within one TIER — a specific org, or (null) the global groups — so a tenant admin's
     *  picker stays org-scoped and an un-drilled super-admin sees only global groups. */
    @Query("select g.id as id, g.name as name from UserGroup g "
            + "where ((:orgId is null and g.orgId is null) or g.orgId = :orgId) "
            + "and lower(g.name) like lower(concat('%', :q, '%')) order by g.name asc")
    List<IdName> searchInOrg(@Param("q") String q, @Param("orgId") UUID orgId, Pageable limit);

    /** Member count for a (visible) group, joined through {@code UserGroup} to stay RLS-scoped. */
    @Query("select count(mem) from UserGroup g "
            + "join UserGroupMember mem on mem.id.groupId = g.id where g.id = :gid")
    int countMembers(@Param("gid") UUID gid);
}
