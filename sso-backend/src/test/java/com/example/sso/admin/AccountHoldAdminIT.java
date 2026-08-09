package com.example.sso.admin;

import com.example.sso.admin.internal.user.api.AccountHoldRequest;
import com.example.sso.admin.internal.user.api.AdminUserController;
import com.example.sso.admin.internal.user.application.AccountHoldStatusView;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditType;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The console's hold controls, driven through the CONTROLLER so the {@code @PreAuthorize} guards are part of
 * what is under test rather than something a service-level call steps around.
 *
 * <p>The allowed caller is tested first and deliberately. A guard whose SpEL names an argument the method does
 * not have fails for the PERMITTED caller only — the refused ones never reach it — so a suite made entirely of
 * 403s can be completely green over an endpoint nobody can use.
 *
 * <p>Both directions are guarded, which is the part worth stating. Placing a hold is restrictive and obviously
 * needs authority; LIFTING one loosens the account's posture, so an unguarded lift would be a way to undo a
 * detection's response — the more valuable of the two to an attacker.
 */
class AccountHoldAdminIT extends AbstractIntegrationTest {

    @Autowired
    AdminUserController controller;
    @Autowired
    AccountHoldService holds;
    @Autowired
    UserService userService;
    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aSuperAdminPlacesAHoldAndItTakesEffect() {
        UserAccount target = plainUser();
        asSuperAdmin();

        controller.holdUser(target.getId(), new AccountHoldRequest("suspected credential theft", 60));

        assertThat(holds.holdInEffect(target.getId())).isPresent();
    }

    @Test
    void theConsoleReadsBackTheHoldItPlaced() {
        UserAccount target = plainUser();
        asSuperAdmin();
        controller.holdUser(target.getId(), new AccountHoldRequest("manual review", 60));

        AccountHoldStatusView view = controller.userHold(target.getId());

        assertThat(view.held()).isTrue();
        assertThat(view.reason()).isEqualTo("manual review");
        assertThat(view.placedBy()).as("a person placed it, so the trail names them").isEqualTo("admin");
        assertThat(view.correlationId()).isNull();
    }

    @Test
    void anAccountWithNoHoldReadsBackAsNotHeld() {
        UserAccount target = plainUser();
        asSuperAdmin();

        AccountHoldStatusView view = controller.userHold(target.getId());

        assertThat(view.held()).isFalse();
        assertThat(view.expiresAt()).isNull();
    }

    @Test
    void liftingRemovesTheHold() {
        UserAccount target = plainUser();
        asSuperAdmin();
        controller.holdUser(target.getId(), new AccountHoldRequest("mistake", 60));

        controller.liftUserHold(target.getId());

        assertThat(holds.holdInEffect(target.getId())).isEmpty();
    }

    /**
     * Holding yourself is refused in both directions. Placing one is a self-lockout for as long as the
     * ceiling allows; lifting your own is the bypass — the held administrator waving their own hold away.
     */
    @Test
    void holdingYourOwnAccountIsRefused() {
        asSuperAdmin();
        UUID self = userService.findByUsernameInOrg("admin", null).orElseThrow().getId();

        assertThatThrownBy(() -> controller.holdUser(self, new AccountHoldRequest("me", 60)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void liftingYourOwnHoldIsRefused() {
        asSuperAdmin();
        UUID self = userService.findByUsernameInOrg("admin", null).orElseThrow().getId();

        assertThatThrownBy(() -> controller.liftUserHold(self)).isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The case the guard exists to PERMIT: a compromised administrator is exactly who a hold is for.
     *
     * <p>Its refusing counterpart — a non-super administrator may not hold one — lives in
     * {@code AdminAccessPolicyTest}, not here. Through the endpoint that refusal is also produced by user
     * scope, so an end-to-end test of it passes whether or not the hold clause exists at all.
     */
    @Test
    void aSuperAdminMayHoldAnotherAdministrator() {
        UUID otherAdmin = adminUser().getId();
        asSuperAdmin();

        controller.holdUser(otherAdmin, new AccountHoldRequest("compromised admin", 60));

        assertThat(holds.holdInEffect(otherAdmin)).isPresent();
    }

    /** The duration ceiling reaches the console too — an administrator cannot hold their way to a disable. */
    @Test
    void aHoldBeyondTheCeilingIsRefused() {
        UserAccount target = plainUser();
        asSuperAdmin();

        assertThatThrownBy(() -> controller.holdUser(target.getId(),
                new AccountHoldRequest("forever", 60 * 24 * 400)))
                .isInstanceOf(BadRequestException.class);
        assertThat(holds.holdInEffect(target.getId())).isEmpty();
    }

    /**
     * The trail names WHO held the account, not who was held.
     *
     * <p>The actor field is what answers "who has been holding people" and what a SIEM correlates on; the
     * held account is the SUBJECT. These rows named the victim as the actor, which collapsed the two.
     */
    @Test
    void aHoldFromTheConsoleIsRecordedAgainstTheAdministratorWithTheUserAsSubject() {
        UserAccount target = plainUser();
        asSuperAdmin();

        controller.holdUser(target.getId(), new AccountHoldRequest("suspected credential theft", 60));

        Map<String, Object> row = ownerJdbc().queryForMap(
                "select principal, subject_type, subject_id, detail from audit_event"
                        + " where type = 'ACCOUNT_HELD' and subject_id = ?", target.getId().toString());
        assertThat(row.get("principal")).as("the acting administrator").isEqualTo("admin");
        assertThat(row.get("subject_type")).isEqualTo("USER");
        assertThat(String.valueOf(row.get("detail"))).contains("user=" + target.getUsername());
    }

    /**
     * The activity view answers "what happened to this person", so it must show actions somebody ELSE
     * performed on them. Moving the actor into the actor field would otherwise have made a hold vanish from
     * the one screen an operator opens to ask why an account is behaving strangely.
     */
    @Test
    void aHoldShowsOnTheHeldUsersActivityEvenThoughSomebodyElseIsTheActor() {
        UserAccount target = plainUser();
        asSuperAdmin();
        controller.holdUser(target.getId(), new AccountHoldRequest("suspected credential theft", 60));

        assertThat(controller.userActivity(target.getId(), 0, 20).items())
                .extracting(AuditEntry::type)
                .contains(AuditType.ACCOUNT_HELD.name());
    }

    private void asSuperAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN),
                        new SimpleGrantedAuthority(Permissions.USER_READ),
                        new SimpleGrantedAuthority(Permissions.USER_UPDATE)))); // the DataSeeder super account
    }

    /** Another administrator — the target the tenant admin must not reach and the super admin must. */
    private UserAccount adminUser() {
        String username = "hold-admin-" + suffix();
        UserAccount account = userService.createUser(new NewUser(
                username, username + "@example.com", "Admin", "S3cret!pw", Set.of(Roles.ADMIN)));
        cleanups.add(() -> orgContext.runAsPlatform(() -> userService.delete(account.getId())));
        return account;
    }

    private UserAccount plainUser() {
        String username = "hold-target-" + suffix();
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "Target", "S3cret!pw", Set.of()));
        cleanups.add(() -> orgContext.runAsPlatform(() -> userService.delete(account.getId())));
        return account;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
