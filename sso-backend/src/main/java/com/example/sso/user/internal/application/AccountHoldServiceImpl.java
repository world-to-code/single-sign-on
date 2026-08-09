package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHeldEvent;
import com.example.sso.user.account.AccountHoldLiftedEvent;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.AccountHoldView;
import com.example.sso.user.account.HoldSpec;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.internal.account.domain.AccountHold;
import com.example.sso.user.internal.account.domain.AccountHoldRepository;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AccountHoldService}.
 *
 * <p>Two scoping decisions here are deliberate and neither is incidental.
 *
 * <p><b>The row is written in the HELD USER's tier</b>, so Postgres' {@code WITH CHECK} stamps it with that
 * tenant and the tenant can then see and lift a hold a platform operator placed on their user. Who may hold
 * whom is decided by the admin guards above this, not by RLS — this module only makes sure the row lands
 * where its subject lives.
 *
 * <p><b>The enforcement read runs as an authoritative, tenant-independent lookup</b>, the posture
 * {@code UserService.orgIdOf} already takes. A hold is a security control, and a control that an unbound
 * context cannot see is one that fails OPEN: the login path resolves its tenant separately, and a read that
 * quietly returned "no hold" whenever the caller's binding was wrong would be the worst possible defect —
 * silent, and only ever in the permissive direction.
 */
@Service
@RequiredArgsConstructor
public class AccountHoldServiceImpl implements AccountHoldService {

    private final AccountHoldRepository holds;
    private final AppUserRepository users;
    private final OrgContext orgContext;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** How long a hold may last. The ceiling is what keeps "hold" from becoming "disable" by argument. */
    @Value("${sso.account-hold.max-duration}")
    private Duration maxDuration;

    @Override
    @Transactional
    public AccountHoldView place(HoldSpec spec) {
        AppUser user = users.findById(spec.userId())
                .orElseThrow(() -> NotFoundException.of("user.notFound"));
        requireUsableExpiry(spec.expiresAt());

        AccountHold hold = orgContext.callInOrg(user.getOrgId(), () -> write(spec, user));

        // Two separate facts. One ENDS the access the account already has — a hold that waited for the next
        // sign-in would leave an intruder's existing session alone, which is the whole thing it exists to stop.
        // The other RECORDS it, as an event because audit already depends on this module to resolve actors.
        events.publishEvent(new UserAccessChangedEvent(user.getUsername(), user.getOrgId()));
        events.publishEvent(new AccountHeldEvent(user.getUsername(), user.getId(), user.getOrgId(),
                hold.getExpiresAt(), hold.getReason(), hold.getCorrelationId()));
        return view(hold);
    }

    @Override
    @Transactional
    public boolean lift(UUID userId) {
        Optional<AppUser> user = users.findById(userId);
        Optional<AccountHold> existing = orgContext.callAsPlatform(() -> holds.findByUserId(userId));
        if (existing.isEmpty()) {
            return false; // nothing to lift; a response system retrying must not be told it failed
        }
        orgContext.callAsPlatform(() -> {
            holds.delete(existing.get());
            return null;
        });
        // No session termination: lifting LOOSENS the account's posture, and sessions established under the
        // hold were already challenged for the second factor it required.
        user.ifPresent(held -> events.publishEvent(
                new AccountHoldLiftedEvent(held.getUsername(), held.getId(), held.getOrgId())));
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountHoldView> holdInEffect(UUID userId) {
        return orgContext.callAsPlatform(
                        () -> holds.findByUserIdAndExpiresAtAfter(userId, clock.instant()))
                .map(this::view);
    }

    /** Places or re-places the row, inside the held user's tier so the WITH CHECK stamps it there. */
    private AccountHold write(HoldSpec spec, AppUser user) {
        AccountHold hold = holds.findByUserId(spec.userId())
                .map(existing -> replace(existing, spec))
                .orElseGet(() -> new AccountHold(spec.userId(), user.getOrgId(), spec.reason(),
                        spec.expiresAt(), spec.placedBy(), spec.correlationId()));
        // Flushed inside the scope: the tier binding is what the WITH CHECK reads, and a write flushed at
        // commit would be evaluated after this scope has already been restored.
        return holds.saveAndFlush(hold);
    }

    private AccountHold replace(AccountHold existing, HoldSpec spec) {
        existing.replaceWith(spec.reason(), spec.expiresAt(), spec.placedBy(), spec.correlationId());
        return existing;
    }

    /**
     * A hold must start in the future and end within the ceiling. The first refusal is the role-grant
     * expiry's: one already past would exist for a single sweep interval and vanish, reading as the system
     * losing it. The second is what stops a response system from placing a hold that outlives its own
     * suspicion — indefinite is what {@code disable} is for, and that decision belongs to a person.
     */
    private void requireUsableExpiry(Instant expiresAt) {
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw BadRequestException.of("user.hold.expiryInPast");
        }
        if (expiresAt.isAfter(now.plus(maxDuration))) {
            throw BadRequestException.of("user.hold.expiryTooFar", maxDuration.toHours());
        }
    }

    private AccountHoldView view(AccountHold hold) {
        return new AccountHoldView(hold.getId(), hold.getUserId(), hold.getReason(), hold.getPlacedAt(),
                hold.getExpiresAt(), hold.getPlacedBy(), hold.getCorrelationId());
    }
}
