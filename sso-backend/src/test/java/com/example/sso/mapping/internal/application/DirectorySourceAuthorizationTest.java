package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditService;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.mapping.internal.domain.MappingRuleCondition;
import com.example.sso.mapping.internal.domain.MappingRuleConditionRepository;
import com.example.sso.mapping.internal.domain.MappingRuleMembershipRepository;
import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.group.UserGroupService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
class DirectorySourceAuthorizationTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TARGET_ROLE = UUID.randomUUID();
    private static final UUID CONFIGURATOR = UUID.randomUUID();

    @Mock private MappingRuleRepository rules;
    @Mock private MappingRuleConditionRepository conditions;
    @Mock private AttributeDefinitionService definitions;
    @Mock private AttributeSourceAuthority sources;
    @Mock private MappingRuleMembershipRepository memberships;
    @Mock private AttributeService attributes;
    @Mock private AuditService audit;
    @Mock private OrgTierGuard tierGuard;
    @Mock private MappingTargetAuthority targetAuthority;
    @Mock private UserGroupService userGroups;
    @Mock private MappingTargetApplier roleApplier;

    private MappingRuleEvaluator evaluator;
    private MappingRule rule;

    @BeforeEach
    void setUp() {
        lenient().when(roleApplier.kind()).thenReturn(MappingTargetKind.ROLE);
        evaluator = new MappingRuleEvaluator(rules, conditions, definitions, sources, memberships, attributes,
                List.of(roleApplier), audit, tierGuard, targetAuthority, userGroups);
        rule = MappingRule.of(MappingTargetKind.ROLE, TARGET_ROLE, ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());

        lenient().when(tierGuard.currentTier()).thenReturn(ORG);
        lenient().when(rules.findByIdForUpdate(any())).thenReturn(Optional.of(rule));
        // The rule's own author is beyond reproach; only the directory's provenance is in question here.
        lenient().when(targetAuthority.authorMayAssign(eq(rule.getCreatedBy()), any(), any())).thenReturn(true);
        // Its condition reads an attribute a DIRECTORY owns — the whole premise of this control.
        lenient().when(conditions.findByRuleId(rule.getId())).thenReturn(List.of(condition("department")));
        lenient().when(definitions.definitionOf(eq(EntityKind.USER), anyString()))
                .thenReturn(Optional.of(directoryOwned()));
    }

    private MappingRuleCondition condition(String key) {
        return MappingRuleCondition.of(rule.getId(), new MappingCondition(key, AttributeOperator.EQUALS, "IT-Admins", List.of()), ORG);
    }

    private AttributeDefinition directoryOwned() {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "department", "Department", null,
                AttributeDataType.STRING, List.of(), false, false, AttributeSource.DIRECTORY, 0);
    }

    private void directoryVouchedForBy(UUID configurator, boolean mayAssign) {
        when(sources.authorsFilling(any()))
                .thenReturn(new AttributeSourceAuthors(Set.of(configurator), true));
        when(targetAuthority.authorMayAssign(eq(configurator), eq(MappingTargetKind.ROLE), eq(TARGET_ROLE)))
                .thenReturn(mayAssign);
    }

    /**
     * The attacker's path: an attribute changed, so ONE user is re-evaluated. Whoever aimed the directory that
     * filled it cannot grant this role by hand, so the directory must not grant it for them.
     */
    @Test
    void aDirectoryWhoseConfiguratorCannotGrantTheRoleDoesNotGrantIt() {
        directoryVouchedForBy(CONFIGURATOR, false);

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);

        verify(memberships, never()).insertClaimIfAbsent(any(), any(), any(), any());
    }

    @Test
    void aDirectoryWhoseConfiguratorCouldGrantTheRoleIsAllowedTo() {
        directoryVouchedForBy(CONFIGURATOR, true);
        when(memberships.insertClaimIfAbsent(any(), any(), any(), any())).thenReturn(1);

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);

        verify(memberships).insertClaimIfAbsent(rule.getId(), USER, TARGET_ROLE, ORG);
    }

    /**
     * A source with no connector at all — SCIM, CSV — fills the key too, and nobody configured a directory we
     * could hold responsible for it. Answering "who can fill this?" with only the connector-backed half would
     * let a legitimate LDAP configurator vouch for a value a SCIM client wrote. The set must be COMPLETE.
     */
    @Test
    void anUnattributableSourceMakesTheAnswerIncompleteEvenAlongsideAGoodConnector() {
        when(sources.authorsFilling(any()))
                .thenReturn(new AttributeSourceAuthors(Set.of(CONFIGURATOR), false));
        lenient().when(targetAuthority.authorMayAssign(eq(CONFIGURATOR), any(), any())).thenReturn(true);

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);

        verify(memberships, never()).insertClaimIfAbsent(any(), any(), any(), any());
    }

    /** An unattributed connector vouches for nothing — fail closed rather than guess who aimed it. */
    @Test
    void anUnattributedDirectoryVouchesForNothing() {
        when(sources.authorsFilling(any()))
                .thenReturn(new AttributeSourceAuthors(Set.of(), false));

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);

        verify(memberships, never()).insertClaimIfAbsent(any(), any(), any(), any());
    }

    /** The same gate on the batch path, which runs when a rule is created or edited. */
    @Test
    void theBatchPathIsGatedToo() {
        directoryVouchedForBy(CONFIGURATOR, false);

        ReflectionTestUtils.invokeMethod(evaluator, "materializeAll", rule, Set.of(USER));

        verify(memberships, never()).insertClaimIfAbsent(any(), any(), any(), any());
    }

    /** A rule that reads no directory-owned attribute is nobody's injection point; it must not be blocked. */
    @Test
    void aRuleReadingOnlyLocallyOwnedAttributesIsUnaffected() {
        when(definitions.definitionOf(eq(EntityKind.USER), anyString())).thenReturn(Optional.of(
                new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "department", "Department", null,
                        AttributeDataType.STRING, List.of(), false, false, AttributeSource.LOCAL, 0)));
        when(memberships.insertClaimIfAbsent(any(), any(), any(), any())).thenReturn(1);

        ReflectionTestUtils.invokeMethod(evaluator, "materialize", rule, USER);

        verify(memberships).insertClaimIfAbsent(rule.getId(), USER, TARGET_ROLE, ORG);
        verify(sources, never()).authorsFilling(any());
    }
}
