package com.example.sso.user.internal.domain;

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

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** (id, username) for the given users — batch name lookup without loading roles/groups. */
    @Query("select u.id as id, u.username as name from AppUser u where u.id in :ids")
    List<IdName> findIdNames(Collection<UUID> ids);

    /** Typeahead search by username (case-insensitive). */
    @Query("select u.id as id, u.username as name from AppUser u "
            + "where lower(u.username) like lower(concat('%', :q, '%')) order by u.username asc")
    List<IdName> search(@Param("q") String q, Pageable limit);

    /**
     * A page of a group's members (id, username), ordered by username. The membership subquery is joined
     * through {@code UserGroup} so it stays tenant-scoped (RLS): members of a group not visible in the
     * current context are not returned.
     */
    @Query("select u.id as id, u.username as name from AppUser u "
            + "where u.id in (select mem.id.userId from UserGroup g "
            + "               join UserGroupMember mem on mem.id.groupId = g.id where g.id = :gid) "
            + "order by u.username asc")
    List<IdName> findGroupMembers(@Param("gid") UUID gid, Pageable page);

    Optional<AppUser> findByUsernameAndOrgId(String username, UUID orgId);

    Optional<AppUser> findByUsernameAndOrgIdIsNull(String username);

    Optional<AppUser> findByEmailAndOrgId(String email, UUID orgId);

    Optional<AppUser> findByEmailAndOrgIdIsNull(String email);

    /**
     * Resolves a user by username WITHIN an organization (the tenant) for a scoped lookup, falling back to a
     * global (org-less) user so the platform super-admin still resolves through a tenant they belong to. A
     * {@code null} orgId is the apex/platform path — only global accounts resolve.
     */
    default Optional<AppUser> findByUsernameInOrg(String username, UUID orgId) {
        if (orgId == null) {
            return findByUsernameAndOrgIdIsNull(username);
        }
        return findByUsernameAndOrgId(username, orgId)
                .or(() -> findByUsernameAndOrgIdIsNull(username));
    }

    /**
     * Resolves a user by email-or-username WITHIN an organization for a scoped lookup (email first, mirroring
     * {@code findByLogin}), preferring an exact org match and falling back to a global (org-less) account. A
     * {@code null} orgId resolves only global accounts (the apex/platform path).
     */
    default Optional<AppUser> findByLoginInOrg(String identifier, UUID orgId) {
        if (orgId == null) {
            return findByEmailAndOrgIdIsNull(identifier)
                    .or(() -> findByUsernameAndOrgIdIsNull(identifier));
        }
        return findByEmailAndOrgId(identifier, orgId)
                .or(() -> findByUsernameAndOrgId(identifier, orgId))
                .or(() -> findByEmailAndOrgIdIsNull(identifier))
                .or(() -> findByUsernameAndOrgIdIsNull(identifier));
    }

    /**
     * A page of users, ordered by username. Roles/permissions are hydrated separately (explicit join
     * repositories) by the service when it projects the page, so no collection fetch-join here.
     */
    Page<AppUser> findByOrderByUsernameAsc(Pageable pageable);

    /** A scoped page: users whose id is in {@code ids} (a delegate's subtree), ordered by username. */
    Page<AppUser> findByIdInOrderByUsernameAsc(Collection<UUID> ids, Pageable pageable);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndOrgId(String username, UUID orgId);

    boolean existsByUsernameAndOrgIdIsNull(String username);

    boolean existsByEmailAndOrgId(String email, UUID orgId);

    boolean existsByEmailAndOrgIdIsNull(String email);

    /** Whether a user with this username already exists WITHIN the organization (or globally when
     *  {@code orgId} is null) — the per-organization uniqueness check for {@code createUser}. */
    default boolean existsByUsernameInOrg(String username, UUID orgId) {
        return orgId == null ? existsByUsernameAndOrgIdIsNull(username) : existsByUsernameAndOrgId(username, orgId);
    }

    /** Whether a user with this email already exists WITHIN the organization (or globally when {@code orgId}
     *  is null). */
    default boolean existsByEmailInOrg(String email, UUID orgId) {
        return orgId == null ? existsByEmailAndOrgIdIsNull(email) : existsByEmailAndOrgId(email, orgId);
    }
}
