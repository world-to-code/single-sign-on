package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep's re-drive policy, which is its own job rather than the evaluator's.
 *
 * <p>A fixed interval is right for a LOST reconcile and wrong for a standing conflict: the last-administrator
 * invariant that refuses a retraction will refuse it again on the next tick, and the refusal row added so an
 * operator could see a stalled rule then arrives every ten minutes forever.
 */
@ExtendWith(MockitoExtension.class)
class MappingReconcileSweeperTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private MappingRuleRepository rules;
    @Mock private MappingRuleEvaluator evaluator;
    @Mock private OrgContext orgContext;
    @Mock private MappingReconcileBackoff backoff;
    @Mock private MappingAuditTrail trail;

    private MappingReconcileSweeper sweeper;
    private MappingRule rule;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        sweeper = new MappingReconcileSweeper(redis, rules, evaluator, orgContext, backoff, trail,
                Duration.ofMinutes(8));
        rule = MappingRule.of(MappingTargetKind.ROLE, UUID.randomUUID(), ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());

        lenient().when(orgContext.callAsPlatform(any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(0)).get());
        lenient().doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(orgContext).runInOrg(eq(ORG), any(Runnable.class));
        lenient().when(rules.findAll()).thenReturn(List.of(rule));
    }

    @Test
    void aDeferredRuleIsNotReDrivenAtAll() {
        when(backoff.deferred(rule.getId())).thenReturn(true);

        sweeper.reconcileAllTiers();

        verify(evaluator, never()).reevaluateRule(any());
    }

    /** A rule that reconciles cleanly starts from zero again, so one transient failure does not linger. */
    @Test
    void aCleanReconcileClearsTheBackoff() {
        when(backoff.deferred(rule.getId())).thenReturn(false);

        sweeper.reconcileAllTiers();

        verify(evaluator).reevaluateRule(rule);
        verify(backoff).recordSuccess(rule.getId());
        verify(backoff, never()).recordFailure(any());
    }

    /** The failure is recorded rather than simply logged — that record is what defers the next attempt. */
    @Test
    void aFailedReconcileDefersTheRuleAndDoesNotStopTheSweep() {
        when(backoff.deferred(rule.getId())).thenReturn(false);
        doThrow(ConflictException.of("admin.lastAdmin")).when(evaluator).reevaluateRule(rule);

        sweeper.reconcileAllTiers(); // the sweep must survive it — other tenants' rules still need driving

        verify(backoff).recordFailure(rule.getId());
        verify(backoff, never()).recordSuccess(any());
        verify(trail, never()).reconcileStalledNow(any()); // not yet stalled, so no announcement
    }

    /** And the one announcement, on the crossing the backoff reports — after it the rule goes quiet. */
    @Test
    void crossingIntoStalledIsAnnouncedOnce() {
        when(backoff.deferred(rule.getId())).thenReturn(false);
        doThrow(ConflictException.of("admin.lastAdmin")).when(evaluator).reevaluateRule(rule);
        when(backoff.recordFailure(rule.getId())).thenReturn(true);

        sweeper.reconcileAllTiers();

        verify(trail).reconcileStalledNow(rule);
    }
}
