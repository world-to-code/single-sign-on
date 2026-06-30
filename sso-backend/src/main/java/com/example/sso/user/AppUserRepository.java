package com.example.sso.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByUsername(String username);

    @EntityGraph(attributePaths = "roles")
    List<AppUser> findByRoles_Id(UUID roleId);

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByExternalId(String externalId);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
