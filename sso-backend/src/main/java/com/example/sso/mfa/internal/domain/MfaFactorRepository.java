package com.example.sso.mfa.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MfaFactorRepository extends JpaRepository<MfaFactor, UUID> {

    Optional<MfaFactor> findByUserIdAndType(UUID userId, MfaType type);

    boolean existsByUserIdAndTypeAndEnabledTrue(UUID userId, MfaType type);

    void deleteByUserId(UUID userId);
}
