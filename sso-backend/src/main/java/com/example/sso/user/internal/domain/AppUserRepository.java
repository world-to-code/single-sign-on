package com.example.sso.user.internal.domain;

import com.example.sso.shared.IdName;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** (id, username) for the given users — batch name lookup without loading roles/groups. */
    @Query("select u.id as id, u.username as name from AppUser u where u.id in :ids")
    List<IdName> findIdNames(Collection<UUID> ids);

    /** (roleId, member) rows for the given roles — one query to resolve members of many roles at once. */
    @Query("select new com.example.sso.user.internal.domain.RoleMemberRow(r.id, u) "
            + "from AppUser u join u.roles r where r.id in :roleIds")
    List<RoleMemberRow> findMembersByRoleIdIn(Collection<UUID> roleIds);

    /** Typeahead search by username (case-insensitive). */
    @Query("select u.id as id, u.username as name from AppUser u "
            + "where lower(u.username) like lower(concat('%', :q, '%')) order by u.username asc")
    List<IdName> search(@Param("q") String q, Pageable limit);

    /** A page of a group's members (id, username), ordered by username. */
    @Query("select u.id as id, u.username as name from AppUser u "
            + "where u.id in (select m from UserGroup g join g.memberUserIds m where g.id = :gid) "
            + "order by u.username asc")
    List<IdName> findGroupMembers(@Param("gid") UUID gid, Pageable page);

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByUsername(String username);

    /** Login/authority path: fetch roles + their permissions + direct permissions in one graph. */
    @EntityGraph(attributePaths = {"roles.permissions", "directPermissions"})
    Optional<AppUser> findWithAuthoritiesByUsername(String username);

    @EntityGraph(attributePaths = "roles")
    List<AppUser> findByRoles_Id(UUID roleId);

    /**
     * A page of users, ordered by username. Deliberately does NOT fetch-join roles/permissions: a
     * collection fetch-join with a {@code Pageable} paginates in memory (HHH000104). The page's rows
     * have their roles/directPermissions batch-loaded ({@code default_batch_fetch_size}) when projected.
     */
    Page<AppUser> findByOrderByUsernameAsc(Pageable pageable);

    /** A scoped page: users whose id is in {@code ids} (a delegate's subtree), ordered by username. */
    Page<AppUser> findByIdInOrderByUsernameAsc(Collection<UUID> ids, Pageable pageable);

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
