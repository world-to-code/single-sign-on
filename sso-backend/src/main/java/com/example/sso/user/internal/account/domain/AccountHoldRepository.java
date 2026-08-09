package com.example.sso.user.internal.account.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Holds by account.
 *
 * <p>The enforcement read is {@link #findByUserIdAndExpiresAtAfter} and its name is the point: a hold that
 * has lapsed constrains nothing whether or not the sweeper has reached its row, so the question is always
 * asked OF A MOMENT. A bare {@code findByUserId} would let a caller mistake a dead row for a live hold, and
 * the reverse mistake — treating a live hold as absent — is the one that fails open.
 */
public interface AccountHoldRepository extends JpaRepository<AccountHold, UUID> {

    /** The hold in force on this account at {@code moment}, if any. */
    Optional<AccountHold> findByUserIdAndExpiresAtAfter(UUID userId, Instant moment);

    /** The row on this account whether or not it is still in force — for placing over it, and for lifting. */
    Optional<AccountHold> findByUserId(UUID userId);

    /** Holds whose time is up, for the sweep that takes them off the table. */
    List<AccountHold> findByExpiresAtLessThanEqual(Instant moment);

    void deleteByUserId(UUID userId);
}
