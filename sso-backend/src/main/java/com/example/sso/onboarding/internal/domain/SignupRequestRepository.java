package com.example.sso.onboarding.internal.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SignupRequestRepository extends JpaRepository<SignupRequest, UUID> {

    Optional<SignupRequest> findByTokenHash(String tokenHash);

    /** The most recent still-open (unredeemed) pending signup for an email — the anti-bomb resend guard reads
     *  this to avoid re-mailing an address while a fresh verification link is already out. Case-insensitive. */
    Optional<SignupRequest> findFirstByAdminEmailIgnoreCaseAndUsedAtIsNullOrderByCreatedAtDesc(String adminEmail);

    /**
     * Atomically consumes an unused signup request: sets {@code used_at} only if still null, returning the
     * number of rows affected. A concurrent second redeem of the same token gets 0 (single-use, race-safe) —
     * the {@code where used_at is null} makes the check-and-set one statement, not a check-then-act.
     */
    @Modifying
    @Query("update SignupRequest s set s.usedAt = :now where s.id = :id and s.usedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
