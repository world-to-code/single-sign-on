package com.example.sso.user.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHoldExpiredEvent;
import com.example.sso.user.internal.account.domain.AccountHold;
import com.example.sso.user.internal.account.domain.AccountHoldRepository;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes lapsed holds off the table and records that they ended by themselves.
 *
 * <p>Note what this is NOT. Unlike the lapsed role-grant sweep, nothing here ends access or closes a window:
 * a hold that is past its expiry has already stopped constraining sign-in, because the read asks whether one
 * is in force at this moment rather than whether a row exists. So the sweep interval is not part of how long
 * a hold lasts, and a sweeper that has been failing quietly leaves accounts LESS constrained than the table
 * suggests, never more.
 *
 * <p>What it does buy is that the table stays the set of holds actually in force, and that an expiry leaves a
 * record. Without that record an investigator reading "held, then nothing" cannot tell a hold that ran its
 * course from one an administrator overruled — opposite facts about the same account.
 *
 * <p>Removing the row is what makes it idempotent: swept once, and the next pass has nothing to find or to
 * re-announce.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class LapsedAccountHoldSweeper {

    private final AccountHoldRepository holds;
    private final AppUserRepository users;
    private final OrgContext orgContext;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${sso.account-hold.sweep-interval}")
    void sweep() {
        try {
            removeLapsedHolds();
        } catch (RuntimeException e) {
            log.error("Lapsed account-hold sweep failed; the rows stay until the next pass", e);
        }
    }

    /** Cross-tenant by design: the clock is not a tenant's, and a scheduled thread is bound to nobody. */
    @Transactional
    void removeLapsedHolds() {
        orgContext.runAsPlatform(this::removeLapsedHoldsInEveryTier);
    }

    private void removeLapsedHoldsInEveryTier() {
        List<AccountHold> lapsed = holds.findByExpiresAtLessThanEqual(clock.instant());
        if (lapsed.isEmpty()) {
            return;
        }

        Map<UUID, AppUser> held = users.findAllById(lapsed.stream().map(AccountHold::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));

        for (AccountHold hold : lapsed) {
            holds.delete(hold);
            // A held account that no longer exists needs no record of its hold ending; the row still goes,
            // because the hold is over either way.
            Optional.ofNullable(held.get(hold.getUserId())).ifPresent(user -> events.publishEvent(
                    new AccountHoldExpiredEvent(user.getUsername(), user.getId(), user.getOrgId())));
        }
        log.info("Removed {} lapsed account hold(s)", lapsed.size());
    }
}
