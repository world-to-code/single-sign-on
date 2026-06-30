package com.example.sso.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MfaFactorRepository extends JpaRepository<MfaFactor, UUID> {

    List<MfaFactor> findByUserId(UUID userId);

    Optional<MfaFactor> findByUserIdAndType(UUID userId, MfaType type);

    boolean existsByUserIdAndTypeAndEnabledTrue(UUID userId, MfaType type);

    void deleteByUserId(UUID userId);
}
