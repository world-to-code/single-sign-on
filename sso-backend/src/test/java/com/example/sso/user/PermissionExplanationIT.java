package com.example.sso.user;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.rbac.DecisionReason;
import com.example.sso.user.rbac.PermissionExplanation;
import com.example.sso.user.rbac.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why a user does or does not hold a permission, answered against a real database.
 *
 * <p>The console used to infer this by subtracting the resolved set from the granted one. That inference is
 * wrong in two directions and both are asserted here: it can name no tier for a refusal, and it counts a
 * permission absent for reasons that were never a refusal at all — nobody ever granting it is not the same
 * event as somebody taking it away, and an administrator acting on the difference needs them separated.
 */
class PermissionExplanationIT extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    private Optional<PermissionExplanation> explanationOf(UUID userId, String permission) {
        return userService.explainPermissions(userId).stream()
                .filter(explanation -> explanation.permission().equals(permission))
                .findFirst();
    }

    @Test
    void aGrantedPermissionIsHeldAndSaysWhichLevelGrantedIt() {
        UserAccount user = user();
        grantDirect(user.getId(), Permissions.USER_READ);

        assertThat(explanationOf(user.getId(), Permissions.USER_READ))
                .hasValueSatisfying(explanation -> {
                    assertThat(explanation.held()).isTrue();
                    assertThat(explanation.decidedBy()).isEqualTo(DecisionReason.ALLOWED_AT_USER_LEVEL);
                });
    }

    /**
     * The distinction the subtraction could not draw. Nothing was ever granted and nothing was taken away —
     * reporting this as a denial would send an administrator looking for a deny that does not exist.
     */
    @Test
    void aPermissionNobodyEverGrantedIsNotReportedAsARefusal() {
        UserAccount user = user();

        assertThat(explanationOf(user.getId(), Permissions.USER_DELETE))
                .hasValueSatisfying(explanation -> {
                    assertThat(explanation.held()).isFalse();
                    assertThat(explanation.decidedBy()).isEqualTo(DecisionReason.NO_LEVEL_SPOKE);
                });
    }

    /** Every catalog permission is accounted for, so the console never has to guess about a missing entry. */
    @Test
    void everyCatalogPermissionGetsAVerdict() {
        UserAccount user = user();

        List<String> explained = userService.explainPermissions(user.getId()).stream()
                .map(PermissionExplanation::permission).toList();

        assertThat(explained).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
    }

    /** An unknown id fails closed — the same contract effectiveAuthorities already keeps. */
    @Test
    void anUnknownUserExplainsNothingRatherThanEverything() {
        assertThat(userService.explainPermissions(UUID.randomUUID())).isEmpty();
    }

    /**
     * The explanation must describe the decision the login path actually made. If the two ever disagree, the
     * console is confidently describing something that did not happen — worse than saying nothing.
     */
    @Test
    void theExplanationAgreesWithTheAuthoritiesTheUserActuallyGets() {
        UserAccount user = user();
        grantDirect(user.getId(), Permissions.USER_READ);
        grantDirect(user.getId(), Permissions.GROUP_READ);

        Set<String> authorities = userService.effectiveAuthorities(user.getId());

        for (PermissionExplanation explanation : userService.explainPermissions(user.getId())) {
            assertThat(authorities.contains(explanation.permission()))
                    .as("%s: explanation says held=%s", explanation.permission(), explanation.held())
                    .isEqualTo(explanation.held());
        }
    }

    /** Inserted directly: this test is about how a grant is EXPLAINED, not about who may make one. */
    private void grantDirect(UUID userId, String permission) {
        ownerJdbc().update("insert into app_user_permission (user_id, permission_id) "
                + "select ?, id from permission where name = ?", userId, permission);
    }

    private UserAccount user() {
        String username = "explain-" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount account = userService.createUser(
                new NewUser(username, username + "@example.com", "Ex", "S3cret!pw", Set.of()));
        cleanups.add(() -> userService.delete(account.getId()));
        return account;
    }
}
