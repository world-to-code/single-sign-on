package com.example.sso.user;

import com.example.sso.shared.IdName;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.UserGroup;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    Optional<UserGroup> findByName(String name);

    Optional<UserGroup> findByExternalId(String externalId);

    List<UserGroup> findAllByOrderByNameAsc();

    Optional<UserGroup> findFirstBySystemTrue();

    /** Groups the given user is a member of (matched at the join table). */
    @Query("select g from UserGroup g where :userId member of g.memberUserIds")
    List<UserGroup> findByMember(@Param("userId") UUID userId);

    /** Ids of the groups the given user belongs to. */
    @Query("select g.id from UserGroup g where :userId member of g.memberUserIds")
    List<UUID> findGroupIdsByMember(@Param("userId") UUID userId);

    /** Whether {@code adminId} manages a group that {@code targetId} is a member of (group scope). */
    @Query("select count(g) > 0 from UserGroup g "
            + "where :adminId member of g.managerUserIds and :targetId member of g.memberUserIds")
    boolean managesUser(@Param("adminId") UUID adminId, @Param("targetId") UUID targetId);

    /** Distinct ids of all users who are members of a group managed by {@code adminId} (group scope). */
    @Query("select distinct m from UserGroup g join g.memberUserIds m where :adminId member of g.managerUserIds")
    List<UUID> memberIdsManagedBy(@Param("adminId") UUID adminId);

    /**
     * Distinct roles delegated to the user via any group they belong to, with permissions fetched —
     * used when building the member's effective authorities at login.
     */
    @Query("select distinct r from UserGroup g join g.roles r left join fetch r.permissions "
            + "where :userId member of g.memberUserIds")
    List<Role> findRolesForMember(@Param("userId") UUID userId);

    /** (id, name) for the given groups — batch name lookup without loading membership. */
    @Query("select g.id as id, g.name as name from UserGroup g where g.id in :ids")
    List<IdName> findIdNames(Collection<UUID> ids);

    /** Typeahead search by name (case-insensitive). */
    @Query("select g.id as id, g.name as name from UserGroup g "
            + "where lower(g.name) like lower(concat('%', :q, '%')) order by g.name asc")
    List<IdName> search(@Param("q") String q, Pageable limit);

    /** Member count without loading the membership collection. */
    @Query("select size(g.memberUserIds) from UserGroup g where g.id = :gid")
    int countMembers(@Param("gid") UUID gid);
}
