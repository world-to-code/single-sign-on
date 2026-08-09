package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.AccountHoldView;
import com.example.sso.user.account.HoldSpec;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.data.TemporalUnitOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * An account hold: the middle state between "nothing" and "disabled" that a response system can place on
 * SUSPICION rather than on proof.
 *
 * <p>Two properties make it safe for a machine to place, and they are what this pins. It <b>ends the access
 * that already exists</b> — a hold that only constrains the next login leaves the session an attacker is
 * sitting in untouched, which is the same mistake as revoking a role without ending the session that carries
 * it. And it <b>stops on its own</b>: the expiry is not advisory, so a hold nobody remembers to lift is not a
 * disabled account by another name.
 *
 * <p>The expiry is asserted the way the role-grant expiry is — by writing the moment rather than waiting for
 * it, because it is the boundary that is worth pinning and not the passage of time. Crucially the reads are
 * asserted BEFORE any sweeper runs: a lapsed row must already have stopped constraining anything, or the
 * sweep interval silently becomes part of the security model.
 */
@RecordApplicationEvents
class AccountHoldIT extends AbstractIntegrationTest {

    @Autowired
    AccountHoldService holds;
    @Autowired
    UserService userService;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;
    @Autowired
    ApplicationEvents events;

    @Test
    void anAccountWithNoHoldHasNoneInEffect() {
        UserAccount user = plainUser();

        assertThat(holds.holdInEffect(user.getId())).isEmpty();
    }

    @Test
    void placingAHoldPutsItInEffectWithItsReasonAndExpiry() {
        UserAccount user = plainUser();
        Instant until = Instant.now().plusSeconds(600);

        holds.place(HoldSpec.byService(user.getId(), "impossible travel", until, "xdr-42"));

        AccountHoldView held = holds.holdInEffect(user.getId()).orElseThrow();
        assertThat(held.reason()).isEqualTo("impossible travel");
        assertThat(held.correlationId()).isEqualTo("xdr-42");
        assertThat(held.expiresAt()).isCloseTo(until, within1s());
    }

    /**
     * The load-bearing read. The row is still on the table — the sweeper has not run — and it already
     * constrains nothing. Anything that asks "is this account held" must ask it OF A MOMENT; an
     * existence check would make the sweep interval part of how long a hold lasts.
     */
    @Test
    void aHoldPastItsExpiryIsNotInEffectEvenThoughItsRowIsStillThere() {
        UserAccount user = heldUntil(Instant.now().plusSeconds(600));

        expireHoldAt(user, Instant.now().minusSeconds(1));

        assertThat(rowCountFor(user)).as("the row is still present").isEqualTo(1);
        assertThat(holds.holdInEffect(user.getId())).isEmpty();
    }

    /** The boundary in the direction that matters: at the instant it expires the hold is over. */
    @Test
    void aHoldExpiringExactlyNowIsAlreadyOver() {
        UserAccount user = heldUntil(Instant.now().plusSeconds(600));

        expireHoldAt(user, Instant.now());

        assertThat(holds.holdInEffect(user.getId())).isEmpty();
    }

    /**
     * Placing a hold ENDS the sessions the account already has. Without this the account is constrained
     * only at its next login, which is precisely the login an attacker already sitting in a session has no
     * reason to perform.
     */
    @Test
    void placingAHoldEndsTheAccountsLiveSessions() {
        UserAccount user = plainUser();

        holds.place(HoldSpec.byService(user.getId(), "endpoint compromised", soon(), "xdr-7"));

        assertThat(terminatedUsernames()).contains(user.getUsername());
    }

    /** Re-placing REPLACES the hold, so extending one is the same call rather than a second row. */
    @Test
    void placingASecondHoldReplacesTheFirstRatherThanStackingRows() {
        UserAccount user = heldUntil(Instant.now().plusSeconds(60));
        Instant later = Instant.now().plusSeconds(900);

        holds.place(HoldSpec.byService(user.getId(), "still suspicious", later, "xdr-9"));

        assertThat(rowCountFor(user)).isEqualTo(1);
        assertThat(holds.holdInEffect(user.getId()).orElseThrow().expiresAt()).isCloseTo(later, within1s());
    }

    @Test
    void liftingRemovesTheHold() {
        UserAccount user = heldUntil(Instant.now().plusSeconds(600));

        assertThat(holds.lift(user.getId())).isTrue();

        assertThat(holds.holdInEffect(user.getId())).isEmpty();
        assertThat(rowCountFor(user)).isZero();
    }

    /** Lifting nothing is not an error — a response system that retries a lift must not be told it failed. */
    @Test
    void liftingWhenNothingIsHeldReportsThatAndDoesNotThrow() {
        UserAccount user = plainUser();

        assertThat(holds.lift(user.getId())).isFalse();
    }

    /**
     * Refused rather than accepted, exactly as a role grant expiring in the past is. A hold already over
     * would exist for one sweep interval and vanish, which reads as the system losing it.
     */
    @Test
    void placingAHoldThatHasAlreadyExpiredIsRefused() {
        UserAccount user = plainUser();

        assertThatThrownBy(() -> holds.place(
                HoldSpec.byService(user.getId(), "late", Instant.now().minusSeconds(1), "xdr-1")))
                .isInstanceOf(BadRequestException.class);
        assertThat(holds.holdInEffect(user.getId())).isEmpty();
    }

    /**
     * The ceiling is what keeps a hold from being a disable in disguise. A machine acting on suspicion may
     * buy time; it may not take an account away indefinitely, and the difference between the two is only
     * ever a number somebody passed.
     */
    @Test
    void placingAHoldBeyondTheCeilingIsRefused() {
        UserAccount user = plainUser();

        assertThatThrownBy(() -> holds.place(
                HoldSpec.byService(user.getId(), "forever", Instant.now().plusSeconds(400 * 24 * 3600), "xdr-2")))
                .isInstanceOf(BadRequestException.class);
        assertThat(holds.holdInEffect(user.getId())).isEmpty();
    }

    /**
     * The row files under the HELD USER'S tenant, not under whoever placed it. A hold placed by a platform
     * operator on a tenant's user is the tenant's to see and to lift, and a row stamped with the caller's
     * tier would be invisible to them.
     */
    @Test
    void theHoldIsStampedWithTheHeldUsersOwnTenant() {
        UUID org = organizations.create(new NewOrganization("hold-" + suffix(), "hold")).id();
        UserAccount user = orgContext.callInOrg(org, () -> userService.createUser(
                new NewUser(name("tenant"), name("tenant") + "@example.com", "Held", "S3cret!pw", Set.of()), org));

        holds.place(HoldSpec.byService(user.getId(), "cross-tier", soon(), "xdr-3"));

        assertThat(ownerJdbc().queryForObject(
                "select org_id from account_hold where user_id = ?", UUID.class, user.getId()))
                .isEqualTo(org);
    }

    /** An administrator's hold records WHO placed it; a machine's records the correlation id instead. */
    @Test
    void anAdministratorsHoldRecordsThePersonAndNoCorrelationId() {
        UserAccount actor = plainUser();
        UserAccount user = plainUser();

        holds.place(HoldSpec.byAdministrator(user.getId(), "manual review", soon(), actor.getId()));

        AccountHoldView held = holds.holdInEffect(user.getId()).orElseThrow();
        assertThat(held.placedBy()).isEqualTo(actor.getId());
        assertThat(held.correlationId()).isNull();
    }

    private Instant soon() {
        return Instant.now().plusSeconds(600);
    }

    private TemporalUnitOffset within1s() {
        return within(1, ChronoUnit.SECONDS);
    }

    private List<String> terminatedUsernames() {
        return events.stream(UserAccessChangedEvent.class).map(UserAccessChangedEvent::username).toList();
    }

    private UserAccount heldUntil(Instant until) {
        UserAccount user = plainUser();
        holds.place(HoldSpec.byService(user.getId(), "suspicious", until, "xdr-0"));
        return user;
    }

    /** Move a live hold's expiry into the past without waiting for it — the boundary, not the clock. */
    private void expireHoldAt(UserAccount user, Instant moment) {
        ownerJdbc().update("update account_hold set expires_at = ? where user_id = ?",
                moment.atOffset(ZoneOffset.UTC), user.getId());
    }

    private int rowCountFor(UserAccount user) {
        return ownerJdbc().queryForObject(
                "select count(*) from account_hold where user_id = ?", Integer.class, user.getId());
    }

    private UserAccount plainUser() {
        String username = name("hold");
        return userService.createUser(new NewUser(username, username + "@example.com", "Hold", "S3cret!pw", Set.of()));
    }

    private String name(String prefix) {
        return prefix + "-" + suffix();
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
