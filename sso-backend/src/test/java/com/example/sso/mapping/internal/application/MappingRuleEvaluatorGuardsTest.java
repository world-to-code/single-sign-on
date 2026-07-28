package com.example.sso.mapping.internal.application;

import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleConditionRepository;
import com.example.sso.mapping.internal.domain.MappingRuleMembership;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.shared.error.ConflictException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Who is allowed to decide that a user MATCHES a privilege-granting rule.
 *
 * <p>Every other check here validates the rule's AUTHOR. That is the wrong question when the rule's condition
 * reads an attribute a directory owns: an attacker holding only {@code directory-connector:write} never needs
 * authority over the target, they only need to control which users satisfy an existing, entirely legitimate
 * rule — point a connector at a directory they run, assert the matching value for themselves, and collect the
 * grant.
 *
 * <p>The gate therefore has to sit on BOTH materialize paths. The batch one runs when a rule is created or
 * edited; the per-user one runs when an attribute changes — which is precisely the attacker's path, and the
 * one that was left uncovered when this control was first written.
 */
@ExtendWith(MockitoExtension.class)
class MappingRuleEvaluatorGuardsTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TARGET_ROLE = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    @Mock private MappingRuleRepository rules;
    @Mock private MappingRuleMembershipRepository memberships;
    @Mock private MappingCohortResolver cohorts;
    @Mock private OrgTierGuard tierGuard;
    @Mock private MappingGrantAdmission admission;
    @Mock private RetractionAdminGuard retractionGuard;
    @Mock private MappingAuditTrail trail;
    @Mock private MappingTargetApplier roleApplier;

    private MappingRuleEvaluator evaluator;
    private MappingRule rule;

    @BeforeEach
    void setUp() {
        lenient().when(roleApplier.kind()).thenReturn(MappingTargetKind.ROLE);
        evaluator = new MappingRuleEvaluator(rules, cohorts, admission, memberships,
                List.of(roleApplier), tierGuard, retractionGuard, trail);
        rule = MappingRule.of(MappingTargetKind.ROLE, TARGET_ROLE, ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());

        lenient().when(tierGuard.currentTier()).thenReturn(ORG);
        lenient().when(rules.findByIdForUpdate(any())).thenReturn(Optional.of(rule));
    }

    /**
     * The wiring seam, and the reason admission is ONE call. Both materialize paths asked the author check and
     * the directory check separately, which is two places for a later one to reach only half — and the cohort
     * path is exactly the one that was left uncovered when the directory check was first written. The logic
     * itself is {@code MappingGrantAdmissionTest}'s; what is asserted here is that neither path skips it.
     */
    @Test
    void neitherMaterializePathGrantsWhatAdmissionRefuses() {
        when(admission.admits(rule)).thenReturn(false);

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);
        ReflectionTestUtils.invokeMethod(evaluator, "materializeAll", rule, Set.of(USER));

        verify(memberships, never()).insertClaimIfAbsent(any(), any(), any(), any());
    }

    /** And both grant when it admits — otherwise the test above would pass with the grant simply broken. */
    @Test
    void bothMaterializePathsGrantWhenAdmissionAllowsIt() {
        when(admission.admits(rule)).thenReturn(true);
        when(memberships.insertClaimIfAbsent(any(), any(), any(), any())).thenReturn(1);

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);
        ReflectionTestUtils.invokeMethod(evaluator, "materializeAll", rule, Set.of(OTHER_USER));

        verify(memberships).insertClaimIfAbsent(rule.getId(), USER, TARGET_ROLE, ORG);
        verify(memberships).insertClaimIfAbsent(rule.getId(), OTHER_USER, TARGET_ROLE, ORG);
    }

    /**
     * The evaluator's share of the last-admin guard: it opens a scope BEFORE it retracts and spends it after.
     * The guard's own behaviour is {@code RetractionAdminGuardTest}'s; what is asserted here is that the
     * evaluator does not skip either half — the pre-state read is the part that cannot be recovered later.
     */
    @Test
    void aRetractionOpensAScopeBeforeItActsAndSpendsItAfterwards() {
        claimedBy(UUID.randomUUID());
        when(retractionGuard.before(any())).thenReturn(new RetractionScope(retractionGuard, ORG, true));
        doThrow(ConflictException.of("admin.lastAdmin")).when(retractionGuard).recount(eq(ORG), anyInt());

        assertThatThrownBy(() -> evaluator.retractAll(rule)).isInstanceOf(ConflictException.class);

        InOrder order = inOrder(retractionGuard, roleApplier);
        order.verify(retractionGuard).before(any());
        order.verify(roleApplier).unassign(any(), any());
        order.verify(retractionGuard).recount(eq(ORG), anyInt());
    }

    /** And the recount is spent ONCE for the whole transaction, not once per member of a retracted cohort. */
    @Test
    void theTierIsRecountedOncePerReevaluationNotPerMember() {
        claimedBy(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(retractionGuard.before(any())).thenReturn(new RetractionScope(retractionGuard, ORG, true));

        evaluator.retractAll(rule);

        verify(retractionGuard, times(1)).recount(eq(ORG), anyInt());
    }

    /** Three members leave the role, so the retraction really happened — the recount is not vacuous. */
    private void claimedBy(UUID... userIds) {
        List<MappingRuleMembership> claims = Arrays.stream(userIds)
                .map(userId -> {
                    MappingRuleMembership claim = mock(MappingRuleMembership.class);
                    lenient().when(claim.getUserId()).thenReturn(userId);
                    lenient().when(claim.getRuleId()).thenReturn(rule.getId());
                    return claim;
                }).toList();
        when(memberships.findByRuleId(rule.getId())).thenReturn(claims);
        for (UUID userId : userIds) {
            lenient().when(memberships.findByUserIdAndTargetId(userId, rule.getTargetId())).thenReturn(List.of());
            lenient().when(memberships.findByRuleIdAndUserId(rule.getId(), userId)).thenReturn(Optional.empty());
        }
    }

    /** A re-evaluation that retracted nothing spends a scope that does nothing. */
    @Test
    void nothingRetractedMeansNoRecount() {
        when(retractionGuard.before(any())).thenReturn(new RetractionScope(retractionGuard, ORG, true));

        evaluator.reevaluateUser(UUID.randomUUID());

        verify(retractionGuard, never()).recount(any(), anyInt());
    }
}
