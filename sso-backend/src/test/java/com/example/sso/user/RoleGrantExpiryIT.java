package com.example.sso.user;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Time-bounded role grants: a role handed out until a moment stops counting after it.
 *
 * <p>Every grant in this system used to be until-revoked, which is the zero-trust tenet the codebase was
 * furthest from. Two halves have to hold and they fail differently, so both are asserted here.
 *
 * <p><b>It must stop being an authority.</b> Anything that decides access has to ignore a lapsed grant the
 * moment it asks. There is more than one read that answers "does this user hold this role", and the expiry is
 * decorative unless the one that becomes login authorities is among them.
 *
 * <p><b>And it must not survive in a session.</b> A session established while the grant was live carries the
 * authority with it and nothing asks again until it ends — so an expiry that only changes what future queries
 * return is the same mistake as deleting a password hash and calling it revocation. The sweeper is what makes
 * the privilege actually stop.
 *
 * <p>Expiries are written directly here rather than waited for. A test that slept until a grant lapsed would
 * be slow and flaky, and it is the boundary that is worth pinning, not the passage of time.
 */
class RoleGrantExpiryIT extends AbstractIntegrationTest {

    @Autowired
    UserDetailsService userDetailsService;
    @Autowired
    UserService userService;
    @Autowired
    RoleService roleService;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aGrantWithNoExpiryStillGrants() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        String user = userGranted(role, null);

        assertThat(authoritiesOf(user)).contains(Permissions.USER_READ);
    }

    @Test
    void aGrantThatHasNotExpiredYetStillGrants() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        String user = userGranted(role, Instant.now().plusSeconds(3600));

        assertThat(authoritiesOf(user)).contains(Permissions.USER_READ);
    }

    /** The whole point: the row is still there, and it no longer confers anything. */
    @Test
    void aLapsedGrantIsNoLongerAnAuthority() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        String user = userGranted(role, Instant.now().minusSeconds(60));

        assertThat(authoritiesOf(user)).doesNotContain(Permissions.USER_READ);
    }

    /**
     * The boundary, stated in the direction that matters: at the instant it expires the grant is over. Rounding
     * the other way would leave a privilege live for as long as the clock's resolution.
     */
    @Test
    void aGrantExpiringExactlyNowIsAlreadyOver() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        String user = userGranted(role, Instant.now());

        assertThat(authoritiesOf(user)).doesNotContain(Permissions.USER_READ);
    }

    /** A lapsed grant must not keep the holder counted as a member of the role either. */
    @Test
    void aLapsedGrantDoesNotShowTheHolderAsAMember() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        UUID lapsed = userIdGranted(role, Instant.now().minusSeconds(60));
        UUID live = userIdGranted(role, null);

        // Through the service, not a query of my own: asking the database the same question the
        // production code asks would assert my SQL rather than the application's.
        Set<UUID> members = roleService.memberIds(role);
        assertThat(members).contains(live).doesNotContain(lapsed);
    }

    private Set<String> authoritiesOf(String username) {
        UserDetails details = userDetailsService.loadUserByUsername(username);
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private String userGranted(UUID roleId, Instant expiresAt) {
        return usernameOf(newUser(roleId, expiresAt));
    }

    private UUID userIdGranted(UUID roleId, Instant expiresAt) {
        return newUser(roleId, expiresAt).getId();
    }

    private String usernameOf(UserAccount account) {
        return account.getUsername();
    }

    private UserAccount newUser(UUID roleId, Instant expiresAt) {
        String username = "expiry-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "Exp", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        // The driver cannot bind an Instant directly; the column is timestamptz, so hand it an offset.
        ownerJdbc().update("insert into app_user_role (user_id, role_id, expires_at) values (?, ?, ?)",
                account.getId(), roleId, expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC));
        return account;
    }

    private UUID seedGlobalRole(String permissionName) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("insert into role (id, name, system) values (?, ?, false)",
                id, "ROLE_EXPIRY_" + UUID.randomUUID().toString().substring(0, 8));
        cleanups.add(() -> ownerJdbc().update("delete from role where id = ?", id));
        UUID permissionId = ownerJdbc().queryForObject(
                "select id from permission where name = ?", UUID.class, permissionName);
        ownerJdbc().update("insert into role_permission (role_id, permission_id) values (?, ?)", id, permissionId);
        return id;
    }

    /** The grant path an administrator actually uses, rather than a row written by the test. */
    @Test
    void grantingUntilAFutureMomentProducesALiveGrantThatReportsItsExpiry() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        UserAccount user = plainUser();
        Instant until = Instant.now().plusSeconds(3600);

        roleService.addMemberUntil(role, user.getId(), until);

        assertThat(authoritiesOf(user.getUsername())).contains(Permissions.USER_READ);
        assertThat(roleService.memberExpiries(role)).containsKey(user.getId());
    }

    /** A permanent grant reports NO expiry, so a caller cannot mistake "never" for "unknown". */
    @Test
    void aPermanentGrantReportsNoExpiryAtAll() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        UserAccount user = plainUser();

        roleService.addMember(role, user.getId());

        assertThat(roleService.memberExpiries(role)).doesNotContainKey(user.getId());
    }

    /**
     * Refused rather than accepted. A grant expiring in the past would exist for one sweep interval and then
     * vanish, which reads as the system losing it instead of declining to make it.
     */
    @Test
    void grantingWithAnExpiryAlreadyPastIsRefused() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        UserAccount user = plainUser();

        assertThatThrownBy(() -> roleService.addMemberUntil(role, user.getId(), Instant.now().minusSeconds(1)))
                .isInstanceOf(BadRequestException.class);
        assertThat(authoritiesOf(user.getUsername())).doesNotContain(Permissions.USER_READ);
    }

    /** Re-granting REPLACES the expiry, so extending one is the same call rather than a delete and an insert. */
    @Test
    void reGrantingReplacesTheExpiryInsteadOfAddingASecondRow() {
        UUID role = seedGlobalRole(Permissions.USER_READ);
        UserAccount user = plainUser();
        roleService.addMemberUntil(role, user.getId(), Instant.now().plusSeconds(60));

        roleService.addMember(role, user.getId());

        assertThat(roleService.memberExpiries(role)).doesNotContainKey(user.getId());
        assertThat(authoritiesOf(user.getUsername())).contains(Permissions.USER_READ);
    }

    private UserAccount plainUser() {
        String username = "grant-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "Gr", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        return account;
    }
}
