package com.example.sso.admin;

import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB coverage for {@link LastAdminGuard} — the parts the mocked unit test cannot reach: the advisory-lock
 * SQL must be valid Postgres and run on the transaction's own connection (so it actually serializes), the tier
 * counts must run against a real schema, and — the whole point of the post-mutation design — a 409 must ROLL
 * BACK the already-issued mutation.
 */
class LastAdminGuardIT extends AbstractIntegrationTest {

    @Autowired
    LastAdminGuard guard;
    @Autowired
    OrganizationService organizations;
    @Autowired
    RoleService roleService;
    @Autowired
    OrgContext orgContext;
    @Autowired
    DenyService denyService;
    @Autowired
    UserService users;
    @Autowired
    PlatformTransactionManager txManager;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    // --- tier counts (single-threaded) ---------------------------------------------------------------

    @Test
    @Transactional
    void platformTierPassesWithTheSeededGlobalAdminAndExercisesTheAdvisoryLock() {
        // DataSeeder provisions an enabled global ROLE_ADMIN; the platform count sees it and the lock SQL runs.
        assertThatCode(() -> guard.ensureTierRetainsAdmin(null)).doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void anUnknownOrgWithoutAnOrgAdminRoleIsANoOp() {
        // No ROLE_ORG_ADMIN for this org -> nothing to guard; also exercises the org-keyed advisory lock.
        assertThatCode(() -> guard.ensureTierRetainsAdmin(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    @Transactional
    void anOrgWhoseProvisionedOrgAdminRoleHasNoEnabledAdminIsRejected() {
        // create() synchronously provisions the org's own ROLE_ORG_ADMIN, but with no member assigned yet — so
        // the org has an admin role and zero effective admins, which is exactly the bricked state to refuse.
        UUID orgId = organizations.create(new NewOrganization("lag-" + shortId(), "Last-Admin-Guard IT")).id();

        assertThatThrownBy(() -> guard.ensureTierRetainsAdmin(orgId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void anOrgWithAnEnabledOrgAdminHoldingUserUpdatePasses() {
        Seeded org = seedOrgWithAdmins(1);

        // The guard runs under the tier's own RLS context, exactly as an admin drilled into the org does.
        transactionTemplate().executeWithoutResult(status -> orgContext.runInOrg(org.orgId, () ->
                assertThatCode(() -> guard.ensureTierRetainsAdmin(org.orgId)).doesNotThrowAnyException()));
    }

    // --- the load-bearing safety property: a 409 rolls the mutation back ------------------------------

    @Test
    void a409FromTheGuardRollsBackTheAlreadyIssuedRemoval() {
        Seeded org = seedOrgWithAdmins(1);
        UUID onlyAdmin = org.adminIds.get(0);

        // Compose the real mutate-then-guard in ONE transaction, exactly as the admin services do: the removal
        // is issued, the guard finds zero surviving admins and throws, and the transaction must roll back.
        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status ->
                orgContext.runInOrg(org.orgId, () -> {
                    roleService.removeMember(org.orgAdminRoleId, onlyAdmin);
                    guard.ensureTierRetainsAdmin(org.orgId);
                }))).isInstanceOf(ConflictException.class);

        // The removal was undone — the sole admin still holds the role (a fresh read, outside that tx).
        assertThat(roleService.members(org.orgAdminRoleId)).extracting(UserAccount::getId).contains(onlyAdmin);
    }

    // --- concurrency: the advisory lock actually serializes two racing removals -----------------------

    @Test
    void twoConcurrentLastAdminRemovalsSerializeSoExactlyOneSucceeds() throws Exception {
        Seeded org = seedOrgWithAdmins(2); // the tier's only two admins
        UUID adminA = org.adminIds.get(0);
        UUID adminB = org.adminIds.get(1);

        int failures = runBothCountingFailures(
                () -> removeAdminInOneTransaction(org.orgAdminRoleId, adminA, org.orgId),
                () -> removeAdminInOneTransaction(org.orgAdminRoleId, adminB, org.orgId));

        // Without a lock on the tx connection both recounts would see the other admin and both would commit,
        // draining the tier to zero. Serialized, the loser's recount sees the winner's committed removal → 409.
        assertThat(failures).isEqualTo(1);
        assertThat(roleService.members(org.orgAdminRoleId)).isNotEmpty(); // the org still has an admin
    }

    private void removeAdminInOneTransaction(UUID roleId, UUID userId, UUID orgId) {
        transactionTemplate().executeWithoutResult(status -> orgContext.runInOrg(orgId, () -> {
            roleService.removeMember(roleId, userId);
            guard.ensureTierRetainsAdmin(orgId);
        }));
    }

    private int runBothCountingFailures(Runnable first, Runnable second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = pool.invokeAll(List.of(
                    committed(barrier, first), committed(barrier, second)));
            int failures = 0;
            for (Future<Boolean> future : futures) {
                if (!future.get()) {
                    failures++;
                }
            }
            return failures;
        } finally {
            pool.shutdown();
        }
    }

    /** Runs the task after the barrier; true if it committed, false if the guard rejected it (409 → rollback). */
    private Callable<Boolean> committed(CyclicBarrier barrier, Runnable task) {
        return () -> {
            barrier.await();
            try {
                task.run();
                return true;
            } catch (ConflictException rejected) {
                return false;
            }
        };
    }

    // --- the deny write path enforces the same invariant (via the LastAdminInvariant port) -----------

    @Test
    void authoringADenyThatStripsTheLastAdminsUserUpdateIsRefusedAndRolledBack() {
        Seeded org = seedOrgWithAdmins(1);
        asSuperAdmin(); // the seeded global ROLE_ADMIN may author any deny; the last-admin guard still constrains it

        // A ROLE deny of user:update on the org's own ROLE_ORG_ADMIN strips its sole admin's appoint capability.
        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status ->
                orgContext.runInOrg(org.orgId, () -> denyService.create(
                        new DenySpec(DenySubjectKind.ROLE, org.orgAdminRoleId, Permissions.USER_UPDATE)))))
                .isInstanceOf(ConflictException.class);

        // rolled back by the 409: no deny row persisted for that role/pattern
        Integer persisted = ownerJdbc().queryForObject(
                "select count(*) from principal_permission_deny where subject_id = ? and pattern = ?",
                Integer.class, org.orgAdminRoleId, Permissions.USER_UPDATE);
        assertThat(persisted).isZero();
    }

    @Test
    void removingAnAdminsLastDirectUserUpdateGrantIsRefusedAndRolledBack() {
        Seeded org = seedOrgWithAdmins(1);
        UUID admin = org.adminIds.get(0);
        // Deny user:update on the org's ROLE_ORG_ADMIN so the admin's role-derived copy is gone, then grant it
        // DIRECTLY on the admin — a USER-specificity grant beats the ROLE deny, so user:update survives ONLY here.
        seedRoleDeny(org.orgAdminRoleId, org.orgId, Permissions.USER_UPDATE);
        grantDirect(admin, Permissions.USER_UPDATE);

        // Dropping that direct grant (setDirectPermissions, a JPA save/delete — a different write shape than the
        // deny path) leaves the admin with no effective user:update. The guard's recount must SEE that write.
        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status ->
                orgContext.runInOrg(org.orgId, () -> {
                    users.setDirectPermissions(admin, Set.of());
                    guard.ensureTierRetainsAdmin(org.orgId);
                }))).isInstanceOf(ConflictException.class);

        // rolled back: the admin still holds the direct user:update grant
        Integer grants = ownerJdbc().queryForObject(
                "select count(*) from app_user_permission ap join permission p on p.id = ap.permission_id "
                        + "where ap.user_id = ? and p.name = ?", Integer.class, admin, Permissions.USER_UPDATE);
        assertThat(grants).isEqualTo(1);
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private void seedRoleDeny(UUID roleId, UUID orgId, String pattern) {
        ownerJdbc().update("insert into principal_permission_deny (id, subject_type, subject_id, org_id, pattern) "
                + "values (gen_random_uuid(), 'ROLE', ?, ?, ?)", roleId, orgId, pattern);
        cleanups.add(() -> ownerJdbc().update("delete from principal_permission_deny where subject_id = ?", roleId));
    }

    private void grantDirect(UUID userId, String permission) {
        ownerJdbc().update("insert into permission (id, name) values (gen_random_uuid(), ?) "
                + "on conflict (name) do nothing", permission);
        ownerJdbc().update("insert into app_user_permission (user_id, permission_id) "
                + "select ?, id from permission where name = ?", userId, permission); // cascades on the app_user delete
    }

    private void asSuperAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN)))); // the DataSeeder super account
    }

    /** A provisioned org plus {@code count} enabled users assigned its own ROLE_ORG_ADMIN (which grants
     *  {@code user:update}), so each is an effective admin. Committed (not in a test tx) so concurrent
     *  transactions and post-rollback reads can see them. */
    private Seeded seedOrgWithAdmins(int count) {
        UUID orgId = organizations.create(new NewOrganization("lag-" + shortId(), "Last-Admin-Guard IT")).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", orgId));
        UUID orgAdminRoleId = roleService.findByName(Roles.ORG_ADMIN, orgId).orElseThrow().getId();

        List<UUID> adminIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID userId = UUID.randomUUID();
            String name = "lag-admin-" + shortId();
            ownerJdbc().update("insert into app_user (id, username, email, org_id, enabled) values (?, ?, ?, ?, true)",
                    userId, name, name + "@example.com", orgId);
            ownerJdbc().update("insert into app_user_role (user_id, role_id) values (?, ?)", userId, orgAdminRoleId);
            cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", userId)); // cascades the role link
            adminIds.add(userId);
        }
        return new Seeded(orgId, orgAdminRoleId, adminIds);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(txManager);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Seeded(UUID orgId, UUID orgAdminRoleId, List<UUID> adminIds) {
    }
}
