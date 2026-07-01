package com.example.sso.user;

import com.example.sso.shared.IdName;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
