package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.authpolicy.policy.AuthPolicySpec;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.HoldSpec;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a live hold COSTS a sign-in.
 *
 * <p>The tenant here runs a password-only policy on purpose. Against the seeded default (password then TOTP)
 * a hold would be unobservable — the second factor was going to be demanded anyway — so a test written there
 * would pass whether or not any of this code existed. Password-only is the configuration where the hold is
 * the only thing standing between a stolen password and a session.
 *
 * <p>Two outcomes, and they fail in opposite directions. A user who HAS a second factor must be challenged
 * for it; a user who has NONE must be refused, because "prove the password again" is not proof of anything
 * when the password is the thing under suspicion. The refusal is the fail-closed half and the one a mistake
 * would silently turn into a completed login.
 */
class AccountHoldLoginGateIT extends AbstractIntegrationTest {

    @Autowired
    AuthStateService authState;
    @Autowired
    AccountHoldService holds;
    @Autowired
    UserService users;
    @Autowired
    OrganizationService organizations;
    @Autowired
    AuthPolicyAdminService authPolicies;
    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();
    private UUID org;
    // Auth-policy priority is unique per tier; each fixture policy takes the next one. Base 10 clears the
    // seeded Defaults (global 0, per-org 1).
    private int nextPriority = 10;

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    /** The control. Without a hold this tenant's policy is satisfied by the password alone. */
    @Test
    void withoutAHoldThePasswordAloneCompletesTheSignIn() {
        UserAccount user = passwordOnlyUser();

        assertThat(describe(user, AuthFactor.PASSWORD).next()).isEqualTo(AuthSessionView.NEXT_DONE);
    }

    @Test
    void aHeldAccountWithAnAvailableSecondFactorIsChallengedForIt() {
        UserAccount user = passwordOnlyUser();
        withVerifiedPhone(user);
        hold(user);

        AuthSessionView view = describe(user, AuthFactor.PASSWORD);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_FACTOR);
        assertThat(view.pendingFactors()).containsExactly(AuthFactor.SMS.name());
        assertThat(view.authenticated()).isFalse();
    }

    /** A verified email is a second factor too — availability is not "did they set up an authenticator app". */
    @Test
    void aVerifiedEmailCountsAsAnAvailableSecondFactor() {
        UserAccount user = passwordOnlyUser();
        users.markEmailVerified(user.getId());
        hold(user);

        assertThat(describe(user, AuthFactor.PASSWORD).pendingFactors()).contains(AuthFactor.EMAIL.name());
    }

    /** Having proven the second factor, the hold has been paid and the sign-in finishes. */
    @Test
    void aHeldAccountThatProvedItsSecondFactorCompletes() {
        UserAccount user = passwordOnlyUser();
        withVerifiedPhone(user);
        hold(user);

        assertThat(describe(user, AuthFactor.PASSWORD, AuthFactor.SMS).next())
                .isEqualTo(AuthSessionView.NEXT_DONE);
    }

    /**
     * The fail-closed half. Nothing this account can prove is independent of the password, so re-typing it
     * would satisfy the hold with the very credential the hold exists to distrust.
     */
    @Test
    void aHeldAccountWithNoSecondFactorAtAllIsRefused() {
        UserAccount user = passwordOnlyUser();
        hold(user);

        AuthSessionView view = describe(user, AuthFactor.PASSWORD);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_ACCOUNT_HELD);
        assertThat(view.authenticated()).isFalse();
        assertThat(view.roles()).as("a refused sign-in reports no authority at all").isEmpty();
    }

    /**
     * The refusal has to reach the COMPLETION gate, not only the view the SPA renders. A state that reads
     * "held" while {@code isPolicySatisfied} still says yes would hand out the MFA-complete marker anyway.
     */
    @Test
    void aRefusedHeldAccountDoesNotSatisfyThePolicy() {
        UserAccount user = passwordOnlyUser();
        hold(user);

        assertThat(authState.isPolicySatisfied(authentication(user, AuthFactor.PASSWORD), org)).isFalse();
    }

    /**
     * The hold's read asks whether one is in force NOW. A row the sweeper has not reached yet must already
     * cost nothing — otherwise the sweep interval quietly becomes part of how long a hold lasts.
     */
    @Test
    void aLapsedHoldCostsTheSignInNothing() {
        UserAccount user = passwordOnlyUser();
        hold(user);
        ownerJdbc().update("update account_hold set expires_at = ? where user_id = ?",
                Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC), user.getId());

        assertThat(describe(user, AuthFactor.PASSWORD).next()).isEqualTo(AuthSessionView.NEXT_DONE);
    }

    /**
     * Before the password is proven the visitor is told what they would have been told anyway. Announcing a
     * hold to somebody who has not yet demonstrated they hold the credential turns the response system's
     * suspicion into an oracle for whoever triggered it.
     */
    @Test
    void aHoldIsNotAnnouncedBeforeTheFirstFactorIsProven() {
        UserAccount user = passwordOnlyUser();
        hold(user);

        AuthSessionView view = describe(user);

        assertThat(view.next()).isEqualTo(AuthSessionView.NEXT_FACTOR);
        assertThat(view.pendingFactors()).containsExactly(AuthFactor.PASSWORD.name());
    }

    /**
     * Enrolment-at-login is the bypass a hold cannot survive. It lets somebody holding only the password
     * register a factor of their own and then satisfy the very step that exists to test whether they are the
     * account's owner — so while held, the answer is no, whatever the tenant's policy says.
     */
    @Test
    void aHeldAccountMayNotEnrolTheSecondFactorItIsBeingAskedFor() {
        UserAccount user = passwordOnlyUser(true);
        withVerifiedPhone(user);

        assertThat(describe(user, AuthFactor.PASSWORD).mfaEnrollmentAllowed())
                .as("the tenant does allow enrolment at login").isTrue();

        hold(user);

        assertThat(describe(user, AuthFactor.PASSWORD).mfaEnrollmentAllowed()).isFalse();
    }

    private AuthSessionView describe(UserAccount user, AuthFactor... satisfied) {
        return authState.describe(authentication(user, satisfied), null, org);
    }

    private Authentication authentication(UserAccount user, AuthFactor... satisfied) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (AuthFactor factor : satisfied) {
            authorities.add(new SimpleGrantedAuthority(factor.authority()));
        }
        return new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
    }

    private void hold(UserAccount user) {
        holds.place(HoldSpec.byService(user.getId(), "suspected credential theft",
                Instant.now().plusSeconds(600), "xdr-gate"));
    }

    private void withVerifiedPhone(UserAccount user) {
        users.enrollPhone(user.getId(), "+821012345678");
        users.markPhoneVerified(user.getId(), "+821012345678");
    }

    private UserAccount passwordOnlyUser() {
        return passwordOnlyUser(false);
    }

    /** A tenant whose login policy is one step: the password. The hold is then the only second factor. */
    private UserAccount passwordOnlyUser(boolean allowEnrollmentAtLogin) {
        if (org == null) {
            org = organizations.create(new NewOrganization("held-" + suffix(), "held")).id();
            cleanups.add(() -> orgContext.runAsPlatform(() -> {
                ownerJdbc().update("delete from policy_binding where org_id = ?", org);
                ownerJdbc().update("delete from auth_policy where org_id = ?", org);
                ownerJdbc().update("delete from organization where id = ?", org);
            }));
        }
        String username = "held-" + suffix();
        UserAccount user = orgContext.callInOrg(org, () -> users.createUser(
                new NewUser(username, username + "@example.com", "Held", "S3cret!pw", Set.of()), org));
        cleanups.add(() -> orgContext.runAsPlatform(() -> users.delete(user.getId())));
        orgContext.runInOrg(org, () -> authPolicies.create(new AuthPolicySpec(
                "password-only-" + suffix(), nextPriority++, true, true, allowEnrollmentAtLogin,
                List.of(Set.of(AuthFactor.PASSWORD)), Set.of(user.getId()), Set.of(), 5)));
        return user;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
