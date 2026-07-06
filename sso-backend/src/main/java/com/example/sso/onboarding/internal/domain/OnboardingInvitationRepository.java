package com.example.sso.onboarding.internal.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardingInvitationRepository extends JpaRepository<OnboardingInvitation, UUID> {

    Optional<OnboardingInvitation> findByTokenHash(String tokenHash);

    /**
     * Atomically consumes an unused invitation: sets {@code used_at} only if still null, returning the number
     * of rows affected. A concurrent second redeem of the same token gets 0 (single-use, race-safe) — the
     * {@code where used_at is null} makes the check-and-set one statement, not a check-then-act.
     */
    @Modifying
    @Query("update OnboardingInvitation i set i.usedAt = :now where i.id = :id and i.usedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
