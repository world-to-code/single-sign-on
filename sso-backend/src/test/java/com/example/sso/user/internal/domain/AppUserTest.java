package com.example.sso.user.internal.domain;

import com.example.sso.user.LockoutPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link AppUser} behavior methods (no setters): enable/disable, email verification,
 * profile edits, the read-only hydrated role/permission views, and the brute-force lockout delegated to
 * the embedded {@link AccountLockout}. Assignment itself is a service concern (explicit join rows), so the
 * aggregate only exposes the hydrated views here. Pure aggregate rules — asserts on resulting state.
 */
class AppUserTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AppUser newUser() {
        return new AppUser("alice", "alice@example.com", "Alice", "hash");
    }

    @Test
    void newUserIsEnabledUnlockedAndUnverified() {
        AppUser user = newUser();

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.isTemporarilyLocked(NOW)).isFalse();
    }

    @Test
    void disableThenEnableFlipsTheFlag() {
        AppUser user = newUser();

        user.disable();
        assertThat(user.isEnabled()).isFalse();

        user.enable();
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void newUserDoesNotRequireAPasswordReset() {
        assertThat(newUser().isPasswordResetRequired()).isFalse();
    }

    @Test
    void requirePasswordResetSetsTheFlagAndChangePasswordClearsIt() {
        AppUser user = newUser();

        user.requirePasswordReset();
        assertThat(user.isPasswordResetRequired()).isTrue();

        user.changePassword("new-hash"); // the user setting their own password satisfies the requirement
        assertThat(user.isPasswordResetRequired()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void verifyEmailSetsTheFlag() {
        AppUser user = newUser();

        user.verifyEmail();

        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    void updateProfileReplacesDisplayNameAndEmail() {
        AppUser user = newUser();

        user.updateProfile("Alice Smith", "alice.smith@example.com");

        assertThat(user.getDisplayName()).isEqualTo("Alice Smith");
        assertThat(user.getEmail()).isEqualTo("alice.smith@example.com");
    }

    @Test
    void hydratedRolesExposeAnUnmodifiableView() {
        AppUser user = newUser();
        Role userRole = new Role("ROLE_USER");

        user.hydrateRoles(List.of(userRole));

        assertThat(user.getRoles()).containsExactly(userRole);
        assertThatThrownBy(() -> user.getRoles().add(new Role("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hydratedDirectPermissionNamesExposeAnUnmodifiableView() {
        AppUser user = newUser();

        user.hydrateDirectPermissionNames(Set.of("user:read", "user:update"));

        assertThat(user.getDirectPermissionNames()).containsExactlyInAnyOrder("user:read", "user:update");
        assertThatThrownBy(() -> user.getDirectPermissionNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failedLoginsBelowThresholdDoNotLock() {
        AppUser user = newUser();

        user.registerFailedLogin(new LockoutPolicy(3, Duration.ofMinutes(15), Duration.ofHours(8)), NOW);
        user.registerFailedLogin(new LockoutPolicy(3, Duration.ofMinutes(15), Duration.ofHours(8)), NOW);

        assertThat(user.isTemporarilyLocked(NOW)).isFalse();
    }

    @Test
    void reachingTheThresholdTemporarilyLocksTheAccount() {
        AppUser user = newUser();

        user.registerFailedLogin(new LockoutPolicy(2, Duration.ofMinutes(15), Duration.ofHours(8)), NOW);
        user.registerFailedLogin(new LockoutPolicy(2, Duration.ofMinutes(15), Duration.ofHours(8)), NOW);

        assertThat(user.isTemporarilyLocked(NOW)).isTrue();
        assertThat(user.isTemporarilyLocked(NOW.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void successfulLoginClearsPriorFailures() {
        AppUser user = newUser();
        user.registerFailedLogin(new LockoutPolicy(2, Duration.ofMinutes(15), Duration.ofHours(8)), NOW);
        user.registerFailedLogin(new LockoutPolicy(2, Duration.ofMinutes(15), Duration.ofHours(8)), NOW);

        user.registerSuccessfulLogin();

        assertThat(user.isTemporarilyLocked(NOW)).isFalse();
    }
}
