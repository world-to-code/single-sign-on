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

    Optional<AppUser> findByUsername(String username);

    /**
     * A page of users, ordered by username. Roles/permissions are hydrated separately (explicit join
     * repositories) by the service when it projects the page, so no collection fetch-join here.
     */
    Page<AppUser> findByOrderByUsernameAsc(Pageable pageable);

    /** A scoped page: users whose id is in {@code ids} (a delegate's subtree), ordered by username. */
    Page<AppUser> findByIdInOrderByUsernameAsc(Collection<UUID> ids, Pageable pageable);

    Optional<AppUser> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
