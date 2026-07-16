package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingRuleView;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.UserGroupService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency guarantees the hardening turns on, exercised against a real Postgres:
 * <ul>
 *   <li>the provenance claim is idempotent ({@code ON CONFLICT DO NOTHING}) so two concurrent re-evaluations of
 *       the same (rule, user) settle without a duplicate-key transaction abort;</li>
 *   <li>a materialize racing a rule delete cannot leave an orphan grant (untracked group membership) — the
 *       per-rule {@code PESSIMISTIC_WRITE} lock serializes the two;</li>
 *   <li>the scheduled full-reconcile sweep re-drives a change whose fire-and-forget event was lost.</li>
 * </ul>
 * White-box: reaches the internal evaluator/repositories/sweeper to drive and observe the async paths directly.
 */
class MappingReconcileConcurrencyIT extends AbstractIntegrationTest {

    @Autowired MappingRuleService mappingRules;
    @Autowired MappingRuleEvaluator evaluator;
    @Autowired MappingReconcileSweeper sweeper;
    @Autowired MappingRuleMembershipRepository memberships;
    @Autowired MappingRuleRepository rules;
    @Autowired UserGroupService groups;
    @Autowired UserService users;
    @Autowired OrgContext orgContext;
    @Autowired PlatformTransactionManager txManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership");
            ownerJdbc().update("delete from mapping_rule");
            createdUsers.forEach(id -> ownerJdbc().update("delete from entity_attribute where entity_id = ?", id.toString()));
            createdUsers.forEach(users::delete);
            createdGroups.forEach(groups::delete);
        });
        createdUsers.clear();
        createdGroups.clear();
    }

    @Test
    void theProvenanceClaimIsIdempotent_secondInsertReturnsZeroWithoutAborting() {
        UUID group = platform(() -> group("eng"));
        UUID rule = platform(() -> UUID.fromString(mappingRules.create(specGroup("dept", "eng", group)).id()));
        UUID user = UUID.randomUUID(); // any id — the claim carries no user FK

        platform(() -> new TransactionTemplate(txManager).execute(status -> {
            assertThat(memberships.insertClaimIfAbsent(rule, user, group, null)).isEqualTo(1);
            assertThat(memberships.insertClaimIfAbsent(rule, user, group, null)).isEqualTo(0); // no duplicate-key abort
            return null;
        }));

        assertThat(provenanceCount(rule)).isEqualTo(1);
    }

    @Test
    void twoConcurrentReevaluationsOfTheSameUserSettleWithoutADuplicateKeyAbort() throws Exception {
        UUID group = platform(() -> group("eng"));
        UUID user = platform(() -> userMatching("dept", "eng")); // matches; no rule yet, so the attr-set event is a no-op
        UUID rule = platform(() -> UUID.fromString(mappingRules.create(specGroup("dept", "eng", group)).id()));

        // Reset to the un-claimed state so BOTH concurrent re-evaluations decide "matches && !claimed" and race.
        platform(() -> {
            ownerJdbc().update("delete from mapping_rule_membership where rule_id = ?", rule);
            groups.removeMember(group, user);
        });

        List<Throwable> failures = runConcurrently(2, () -> platform(() -> {
            evaluator.reevaluateUser(user);
            return null;
        }));

        assertThat(failures).isEmpty();               // the loser upserts 0 rows, never aborts on the unique constraint
        assertThat(provenanceCount(rule)).isEqualTo(1);
        assertThat(platform(() -> groups.groupIdsOf(user))).contains(group);
    }

    @Test
    void aMaterializeRacingARuleDeleteLeavesNoOrphanGrant() throws Exception {
        UUID group = platform(() -> group("eng"));
        UUID racer = platform(this::plainUser); // no attribute yet → does NOT match the rule
        UUID rule = platform(() -> UUID.fromString(mappingRules.create(specGroup("dept", "eng", group)).id()));
        // Make the racer match WITHOUT firing the async listener (direct row = a change the event-path never saw).
        platform(() -> tagUser(racer, "dept", "eng"));

        CountDownLatch materializeHoldsLock = new CountDownLatch(1);
        CountDownLatch deleteDispatched = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A: materialize the racer inside a held transaction — it takes the rule's write-lock, then
            // waits so the delete has to queue behind it.
            Future<?> materialize = pool.submit(() -> orgContext.runAsPlatform(() ->
                    new TransactionTemplate(txManager).executeWithoutResult(status -> {
                        evaluator.reevaluateUser(racer); // locks the rule + inserts provenance + grants the group
                        materializeHoldsLock.countDown();
                        awaitQuietly(deleteDispatched);
                        sleepQuietly(400); // give the delete time to actually block on the rule lock
                    })));
            // Thread B: delete the rule — blocks on the lock until A commits, then its retract sees the new
            // provenance and revokes the grant before dropping the rule.
            Future<?> delete = pool.submit(() -> {
                awaitQuietly(materializeHoldsLock);
                deleteDispatched.countDown();
                orgContext.runAsPlatform(() -> mappingRules.delete(rule));
            });
            materialize.get(20, TimeUnit.SECONDS);
            delete.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(platform(() -> groups.groupIdsOf(racer))).doesNotContain(group); // no orphan grant
        assertThat(count("select count(*) from mapping_rule_membership where rule_id = ?", rule)).isZero();
        assertThat(count("select count(*) from mapping_rule where id = ?", rule)).isZero();
    }

    @Test
    void theSweepReDrivesAChangeWhoseEventWasLost() {
        UUID group = platform(() -> group("eng"));
        UUID rule = platform(() -> UUID.fromString(mappingRules.create(specGroup("dept", "eng", group)).id()));
        UUID missed = platform(this::plainUser); // present, but does not match yet
        // A matching attribute written straight to the table (as if the AFTER_COMMIT @Async event never arrived).
        platform(() -> tagUser(missed, "dept", "eng"));
        assertThat(platform(() -> groups.groupIdsOf(missed))).doesNotContain(group); // no event fired → not yet mapped

        sweeper.reconcileAllTiers();

        assertThat(platform(() -> groups.groupIdsOf(missed))).contains(group); // the durability net converged it
        assertThat(provenanceCount(rule)).isEqualTo(1);
    }

    // --- helpers ---

    private List<Throwable> runConcurrently(int threads, Runnable action) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                action.run();
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                failures.add(e.getCause() != null ? e.getCause() : e);
            }
        }
        pool.shutdownNow();
        return failures;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> T platform(Supplier<T> action) {
        return orgContext.callAsPlatform(action);
    }

    private void platform(Runnable action) {
        orgContext.runAsPlatform(action);
    }

    private MappingRuleSpec specGroup(String key, String value, UUID group) {
        return new MappingRuleSpec(key, AttributeOperator.EQUALS, value, MappingTargetKind.GROUP, group);
    }

    private UUID group(String prefix) {
        UUID id = UUID.fromString(groups.create(new GroupSpec(prefix + "-" + suffix(), null, null, Set.of())).id());
        createdGroups.add(id);
        return id;
    }

    /** A global user carrying {@code key = value} from the start. */
    private UUID userMatching(String key, String value) {
        UUID id = plainUser();
        tagUser(id, key, value);
        return id;
    }

    /** A global user with no metadata attribute. */
    private UUID plainUser() {
        String s = suffix();
        UUID id = users.createUser(new NewUser("u-" + s, "u-" + s + "@example.com", "U " + s,
                "S3cret!pw9", Set.of("ROLE_USER")), null).getId();
        createdUsers.add(id);
        return id;
    }

    /** Write an attribute straight to the table so NO async re-evaluation event fires — the test drives the
     *  evaluator/sweeper itself, free of listener interference. */
    private void tagUser(UUID userId, String key, String value) {
        ownerJdbc().update(
                "insert into entity_attribute (entity_kind, entity_id, attr_key, attr_value, org_id) values (?,?,?,?,null)",
                "USER", userId.toString(), key, value);
    }

    private int provenanceCount(UUID ruleId) {
        return count("select count(*) from mapping_rule_membership where rule_id = ?", ruleId);
    }

    private int count(String sql, UUID arg) {
        Integer n = ownerJdbc().queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
