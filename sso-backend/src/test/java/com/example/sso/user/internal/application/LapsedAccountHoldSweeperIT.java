package com.example.sso.user.internal.application;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.AccountHoldExpiredEvent;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.HoldSpec;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sweep that takes lapsed holds off the table.
 *
 * <p>Note what it is NOT for. A lapsed hold has already stopped constraining anything — the read asks whether
 * a hold is in effect at this moment, not whether a row exists — so unlike the role-grant sweep this one ends
 * no access and closes no window. It removes the row so the table stays the set of holds that are actually in
 * force, and it emits the record that the hold ended by itself rather than by anybody's decision. That record
 * is the point: an investigator reading "held, then nothing" cannot tell an expiry from a lift.
 */
@RecordApplicationEvents
class LapsedAccountHoldSweeperIT extends AbstractIntegrationTest {

    @Autowired
    LapsedAccountHoldSweeper sweeper;
    @Autowired
    AccountHoldService holds;
    @Autowired
    UserService userService;
    @Autowired
    ApplicationEvents events;

    @Test
    void aLapsedHoldIsRemovedAndRecordedAsHavingExpired() {
        UserAccount user = heldThenLapsed();

        sweeper.removeLapsedHolds();

        assertThat(rowCountFor(user)).isZero();
        assertThat(expiredFor()).contains(user.getUsername());
    }

    /** A hold still in force is left alone — the sweep must not shorten one. */
    @Test
    void aLiveHoldSurvivesTheSweep() {
        UserAccount user = plainUser();
        holds.place(HoldSpec.byService(user.getId(), "live", Instant.now().plusSeconds(600), "xdr-live"));

        sweeper.removeLapsedHolds();

        assertThat(holds.holdInEffect(user.getId())).isPresent();
    }

    /**
     * An expiry LOOSENS the account's posture, so it must not end sessions. Terminating on expiry would
     * log out the very person whose hold just finished — a self-inflicted sign-out with no cause, and one
     * that would repeat for every held account at once.
     */
    @Test
    void anExpiryDoesNotEndAnybodysSessions() {
        UserAccount user = heldThenLapsed();
        int terminationsBefore = terminations().size();

        sweeper.removeLapsedHolds();

        assertThat(terminations()).hasSize(terminationsBefore);
    }

    /** Running twice is harmless: the row is gone, so the second pass has nothing to find or re-announce. */
    @Test
    void sweepingTwiceRecordsTheExpiryOnlyOnce() {
        UserAccount user = heldThenLapsed();

        sweeper.removeLapsedHolds();
        sweeper.removeLapsedHolds();

        assertThat(expiredFor()).filteredOn(user.getUsername()::equals).hasSize(1);
    }

    private UserAccount heldThenLapsed() {
        UserAccount user = plainUser();
        holds.place(HoldSpec.byService(user.getId(), "lapsing", Instant.now().plusSeconds(600), "xdr-lapse"));
        ownerJdbc().update("update account_hold set expires_at = ? where user_id = ?",
                Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC), user.getId());
        return user;
    }

    private List<String> expiredFor() {
        return events.stream(AccountHoldExpiredEvent.class).map(AccountHoldExpiredEvent::username).toList();
    }

    private List<String> terminations() {
        return events.stream(UserAccessChangedEvent.class).map(UserAccessChangedEvent::username).toList();
    }

    private int rowCountFor(UserAccount user) {
        return ownerJdbc().queryForObject(
                "select count(*) from account_hold where user_id = ?", Integer.class, user.getId());
    }

    private UserAccount plainUser() {
        String username = "sweep-" + UUID.randomUUID().toString().substring(0, 8);
        return userService.createUser(
                new NewUser(username, username + "@example.com", "Sweep", "S3cret!pw", Set.of()));
    }
}
