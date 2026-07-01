package com.example.sso.user;

import com.example.sso.shared.IdName;
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

    /** (roleId, user) rows for the given roles — one query to resolve members of many roles at once. */
    @Query("select r.id, u from AppUser u join u.roles r where r.id in :roleIds")
    List<Object[]> findMembersByRoleIdIn(Collection<UUID> roleIds);

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

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByExternalId(String externalId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
