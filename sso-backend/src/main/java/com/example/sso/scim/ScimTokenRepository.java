package com.example.sso.scim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScimTokenRepository extends JpaRepository<ScimToken, UUID> {

    Optional<ScimToken> findByTokenHash(String tokenHash);

    boolean existsByTokenHash(String tokenHash);
}
