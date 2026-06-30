package com.example.sso.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    Optional<UserGroup> findByName(String name);

    Optional<UserGroup> findByExternalId(String externalId);

    List<UserGroup> findAllByOrderByNameAsc();

    /** Groups the given user is a member of (matched at the join table). */
    @Query("select g from UserGroup g where :userId member of g.memberUserIds")
    List<UserGroup> findByMember(@Param("userId") UUID userId);
}
