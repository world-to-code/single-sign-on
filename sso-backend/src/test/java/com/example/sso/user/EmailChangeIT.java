package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A user's email is a LOGIN IDENTIFIER (sign-in resolves username OR email within the org) and the address
 * email-OTP codes are delivered to. Changing it therefore has two invariants, proven here against the real
 * database: it stays unique within the owning organization, and a changed address is unproven — the verified
 * flag drops, so an admin cannot silently redirect a user's codes and recovery mail to an address they
 * control. Uniqueness is PER ORG: two tenants may hold the same address for different people.
 */
class EmailChangeIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        createdUsers.forEach(userService::delete); // app_user has an FK to organization; users go first
        createdUsers.clear();
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private UUID org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private UserAccount user(String username, String email, UUID orgId) {
        UserAccount created = userService.createUser(
                new NewUser(username, email, "U", "S3cret!pw", Set.of()), orgId);
        createdUsers.add(created.getId());
        return created;
    }

    private UserUpdate withEmail(String email) {
        return new UserUpdate("U", email, true, null);
    }

    @Test
    void anEmailAlreadyHeldInTheSameOrgIsRejected() {
        orgA = org("email-it");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount taker = user("taker-" + suffix, "taken-" + suffix + "@example.com", orgA);
        UserAccount mover = user("mover-" + suffix, "mover-" + suffix + "@example.com", orgA);

        assertThatThrownBy(() -> userService.updateUser(mover.getId(), withEmail(taker.getEmail())))
                .isInstanceOf(ConflictException.class);
        assertThat(userService.findById(mover.getId()).orElseThrow().getEmail())
                .isEqualTo(mover.getEmail()); // the rejected write mutated nothing
    }

    @Test
    void theSameEmailMayExistInADifferentOrg() {
        orgA = org("email-a");
        orgB = org("email-b");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String shared = "shared-" + suffix + "@example.com";
        user("a-" + suffix, shared, orgA);
        UserAccount inB = user("b-" + suffix, "other-" + suffix + "@example.com", orgB);

        // Identity is per-tenant: orgB's user may take an address orgA already uses for someone else.
        assertThatCode(() -> userService.updateUser(inB.getId(), withEmail(shared))).doesNotThrowAnyException();
        assertThat(userService.findById(inB.getId()).orElseThrow().getEmail()).isEqualTo(shared);
    }

    @Test
    void changingTheEmailRequiresItToBeVerifiedAgain() {
        orgA = org("email-verify");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = user("verify-" + suffix, "before-" + suffix + "@example.com", orgA);
        userService.markEmailVerified(user.getId());
        assertThat(userService.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();

        userService.updateUser(user.getId(), withEmail("after-" + suffix + "@example.com"));

        assertThat(userService.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void resubmittingTheCurrentEmailNeitherCollidesNorUnverifiesIt() {
        orgA = org("email-noop");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserAccount user = user("noop-" + suffix, "same-" + suffix + "@example.com", orgA);
        userService.markEmailVerified(user.getId());

        // Editing only the display name must not collide with the user's own address, nor unverify it.
        userService.updateUser(user.getId(), new UserUpdate("Renamed", user.getEmail(), true, null));

        UserAccount after = userService.findById(user.getId()).orElseThrow();
        assertThat(after.getDisplayName()).isEqualTo("Renamed");
        assertThat(after.isEmailVerified()).isTrue();
    }
}
