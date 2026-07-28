package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleCondition;
import com.example.sso.mapping.internal.domain.MappingRuleConditionRepository;
import com.example.sso.mapping.internal.domain.MappingRuleMembership;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.deny.LastAdminInvariant;
import com.example.sso.user.group.UserGroupService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pass that runs INSIDE an interactive write — a profile move that has just deleted the attributes some
 * rules read — and therefore has to be affordable, complete, and honest about what it did.
 *
 * <p>Its predecessor was the general reconcile, which is none of those three inside a request: it reads every
 * rule and every condition in the tier, its materialize branch takes a row lock per rule, and its single pass
 * over a snapshot of the attribute set cannot follow a retraction that changes that set.
 */
@ExtendWith(MockitoExtension.class)
class SynchronousRetractionTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TEAM_GROUP = UUID.randomUUID();
    private static final UUID ADMIN_ROLE = UUID.randomUUID();

    @Mock private MappingRuleRepository rules;
    @Mock private MappingRuleMembershipRepository memberships;
    @Mock private MappingCohortResolver cohorts;
    @Mock private OrgTierGuard tierGuard;
    @Mock private MappingGrantAdmission admission;
    @Mock private UserGroupService userGroups;
    @Mock private LastAdminInvariant lastAdminInvariant;
    @Mock private MappingAuditTrail trail;
    @Mock private MappingTargetApplier groupApplier;
    @Mock private MappingTargetApplier roleApplier;

    private MappingRuleEvaluator evaluator;
    private MappingRule groupRule;
    private MappingRule roleRule;

    @BeforeEach
    void setUp() {
        lenient().when(groupApplier.kind()).thenReturn(MappingTargetKind.GROUP);
        lenient().when(roleApplier.kind()).thenReturn(MappingTargetKind.ROLE);
        evaluator = new MappingRuleEvaluator(rules, cohorts, admission, memberships,
                List.of(groupApplier, roleApplier), tierGuard, userGroups, lastAdminInvariant, trail);
        lenient().when(tierGuard.currentTier()).thenReturn(ORG);

        groupRule = ruleOf(MappingTargetKind.GROUP, TEAM_GROUP);   // level=staff  -> GROUP Team
        roleRule = ruleOf(MappingTargetKind.ROLE, ADMIN_ROLE);     // dept=eng     -> ROLE OrgAdmin
    }

    /**
     * The chain a single pass gets wrong. The user's {@code level} attribute is gone, so the GROUP rule stops
     * matching and the group goes — and {@code dept=eng} was inherited FROM that group, so the ROLE rule
     * stops matching too. A pass that reads the attribute set once still sees {@code dept=eng} and leaves the
     * role in place: the caller then terminates the sessions while {@code app_user_role} still carries it,
     * which is the exact window this whole synchronous path exists to close.
     */
    @Test
    void aRetractionThatShrinksInheritedAttributesRetractsWhatDependedOnThem() {
        claims(groupRule, roleRule);
        // Round 1 still sees dept=eng, inherited FROM the group; round 2, after the group goes, sees nothing.
        // Two consecutive answers, because the shrink is the whole point — a single read cannot show it.
        when(cohorts.effectiveAttributes(USER)).thenReturn(List.of(new Attribute("dept", "eng")), List.of());

        evaluator.retractStaleClaims(USER);

        verify(groupApplier).unassign(TEAM_GROUP, USER);
        verify(roleApplier).unassign(ADMIN_ROLE, USER);
    }

    /** Affordability, stated as behaviour: no tier-wide scan, no row lock, and no grant is ever made. */
    @Test
    void itReadsOnlyTheRulesThatClaimTheUserAndNeverMaterializes() {
        claims(groupRule);
        when(cohorts.effectiveAttributes(USER)).thenReturn(List.of());

        evaluator.retractStaleClaims(USER);

        verify(rules, never()).findAll();
        verify(cohorts, never()).allConditionsGroupedByRule(); // the async pass's tier-wide read
        verify(rules, never()).findByIdForUpdate(any());        // the materialize branch's pessimistic lock
        verify(memberships, never()).insertClaimIfAbsent(any(), any(), any(), any());
    }

    /** A user no rule claims costs nothing at all — not even the pre-state read behind the last-admin guard. */
    @Test
    void aUserWithNoClaimsDoesNoWork() {
        when(memberships.findByUserId(USER)).thenReturn(List.of());

        evaluator.retractStaleClaims(USER);

        verify(rules, never()).findAllById(any());
        verify(lastAdminInvariant, never()).retractionWouldNeedGuarding(any(), any());
        verify(cohorts, never()).effectiveAttributes(any());
    }

    /**
     * The audit half, as this class's share of it: the evaluator says WHAT happened and to which kind of row,
     * and {@link MappingAuditTrail} owns which of those survive a rollback (its own test pins that). Asserted
     * here because the split only works if the retraction reports the change AND the refusal reports itself —
     * a refusal that told the trail nothing would leave a rule silently not converging.
     */
    @Test
    void aRefusalIsReportedSeparatelyFromTheRetractionItUndid() {
        claims(roleRule);
        when(cohorts.effectiveAttributes(USER)).thenReturn(List.of());
        when(lastAdminInvariant.retractionWouldNeedGuarding(any(), any())).thenReturn(true);
        ConflictException refused = ConflictException.of("admin.lastAdmin");
        doThrow(refused).when(lastAdminInvariant).ensureRetractionRetainsAdmin(ORG);

        assertThatThrownBy(() -> evaluator.retractStaleClaims(USER)).isInstanceOf(ConflictException.class);

        verify(trail).changedMembership(AuditType.MAPPING_RULE_RETRACTED, roleRule, USER);
        verify(trail).refusalNow(ORG, 1, refused);
    }

    /** The user is claimed by these rules, and none of them still matches (no attributes are stubbed in). */
    private void claims(MappingRule... claimed) {
        List<MappingRuleMembership> rows = new ArrayList<>();
        for (MappingRule rule : claimed) {
            MappingRuleMembership row = mock(MappingRuleMembership.class);
            lenient().when(row.getRuleId()).thenReturn(rule.getId());
            rows.add(row);
            lenient().when(memberships.findByUserIdAndTargetId(USER, rule.getTargetId())).thenReturn(List.of());
            lenient().when(memberships.findByRuleIdAndUserId(rule.getId(), USER)).thenReturn(Optional.empty());
        }
        when(memberships.findByUserId(USER)).thenReturn(rows);
        when(rules.findAllById(any())).thenReturn(List.of(claimed));
        when(cohorts.conditionsGroupedByRule(any())).thenReturn(conditionsOf(claimed));
        // The resolver's own matchesAll IS this predicate; delegating keeps the mock from asserting a matching
        // rule matches, which would make every retraction below true by stub rather than by condition.
        lenient().when(cohorts.matchesAll(any(), any())).thenAnswer(invocation -> {
            List<MappingCondition> conditions = invocation.getArgument(0);
            List<Attribute> held = invocation.getArgument(1);
            return !conditions.isEmpty()
                    && conditions.stream().allMatch(condition -> condition.toPredicate().matches(held));
        });
    }

    /** Each rule's condition, keyed by rule: the GROUP rule reads level=staff, the ROLE rule reads dept=eng. */
    private Map<UUID, List<MappingCondition>> conditionsOf(MappingRule... claimed) {
        Map<UUID, List<MappingCondition>> byRule = new HashMap<>();
        for (MappingRule rule : claimed) {
            boolean group = rule.getThenKind() == MappingTargetKind.GROUP;
            byRule.put(rule.getId(), List.of(new MappingCondition(group ? "level" : "dept",
                    AttributeOperator.EQUALS, group ? "staff" : "eng", List.of())));
        }
        return byRule;
    }

    private MappingRule ruleOf(MappingTargetKind kind, UUID targetId) {
        MappingRule rule = MappingRule.of(kind, targetId, ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        return rule;
    }
}
