package com.example.sso.mapping.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.mapping.MappingCondition;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.EntityKind;
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
 * <p>Every other check validates the rule's AUTHOR. That is the wrong question when the rule's condition reads
 * an attribute a directory owns: an attacker holding only {@code directory-connector:write} never needs
 * authority over the target, they only need to control which users satisfy an existing, entirely legitimate
 * rule — point a connector at a directory they run, assert the matching value for themselves, and collect the
 * grant.
 *
 * <p>Asked directly of the admission object now. It used to be reached by reflecting into two private
 * evaluator methods, which is also why the batch path could be — and once was — left uncovered.
 */
@ExtendWith(MockitoExtension.class)
class MappingGrantAdmissionTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET_ROLE = UUID.randomUUID();
    private static final UUID CONFIGURATOR = UUID.randomUUID();

    @Mock private MappingTargetAuthority targetAuthority;
    @Mock private AttributeDefinitionService definitions;
    @Mock private AttributeSourceAuthority sources;
    @Mock private MappingCohortResolver cohorts;
    @Mock private MappingAuditTrail trail;

    private MappingGrantAdmission admission;
    private MappingRule rule;

    @BeforeEach
    void setUp() {
        admission = new MappingGrantAdmission(targetAuthority, definitions, sources, cohorts, trail);
        rule = MappingRule.of(MappingTargetKind.ROLE, TARGET_ROLE, ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());

        // The rule's own author is beyond reproach; only the directory's provenance is in question here.
        lenient().when(targetAuthority.authorMayAssign(eq(rule.getCreatedBy()), any(), any())).thenReturn(true);
        // Its condition reads an attribute a DIRECTORY owns — the whole premise of this control.
        lenient().when(cohorts.conditionsOf(rule.getId()))
                .thenReturn(List.of(new MappingCondition("department", AttributeOperator.EQUALS, "IT-Admins",
                        List.of())));
        lenient().when(definitions.definitionOf(eq(EntityKind.USER), anyString()))
                .thenReturn(Optional.of(ownedBy(AttributeSource.DIRECTORY)));
    }

    /** The attacker's path: whoever aimed the directory cannot grant this role by hand, so it must not for them. */
    @Test
    void aDirectoryWhoseConfiguratorCannotGrantTheRoleDoesNotAdmitTheGrant() {
        directoryVouchedForBy(CONFIGURATOR, false);

        assertThat(admission.admits(rule)).isFalse();
        verify(trail).changedGrantAdmission(AuditType.MAPPING_RULE_DIRECTORY_SOURCE_UNAUTHORIZED, rule);
    }

    @Test
    void aDirectoryWhoseConfiguratorCouldGrantTheRoleIsAllowedTo() {
        directoryVouchedForBy(CONFIGURATOR, true);

        assertThat(admission.admits(rule)).isTrue();
    }

    /**
     * A source with no connector at all — SCIM, CSV — fills the key too, and nobody configured a directory we
     * could hold responsible for it. Answering "who can fill this?" with only the connector-backed half would
     * let a legitimate LDAP configurator vouch for a value a SCIM client wrote. The set must be COMPLETE.
     */
    @Test
    void anUnattributableSourceMakesTheAnswerIncompleteEvenAlongsideAGoodConnector() {
        when(sources.authorsFilling(any())).thenReturn(new AttributeSourceAuthors(Set.of(CONFIGURATOR), false));
        lenient().when(targetAuthority.authorMayAssign(eq(CONFIGURATOR), any(), any())).thenReturn(true);

        assertThat(admission.admits(rule)).isFalse();
    }

    /** An unattributed connector vouches for nothing — fail closed rather than guess who aimed it. */
    @Test
    void anUnattributedDirectoryVouchesForNothing() {
        when(sources.authorsFilling(any())).thenReturn(new AttributeSourceAuthors(Set.of(), false));

        assertThat(admission.admits(rule)).isFalse();
    }

    /** A rule that reads no directory-owned attribute is nobody's injection point; it must not be blocked. */
    @Test
    void aRuleReadingOnlyLocallyOwnedAttributesIsUnaffected() {
        when(definitions.definitionOf(eq(EntityKind.USER), anyString()))
                .thenReturn(Optional.of(ownedBy(AttributeSource.LOCAL)));

        assertThat(admission.admits(rule)).isTrue();
        verify(sources, never()).authorsFilling(any());
    }

    /**
     * The other question, and it is asked FIRST — a rule whose author has since been demoted stops conferring
     * what they could no longer confer by hand, whatever the directory says.
     */
    @Test
    void anAuthorWhoLostTheAuthorityStopsTheGrantBeforeTheDirectoryIsConsulted() {
        when(targetAuthority.authorMayAssign(eq(rule.getCreatedBy()), any(), any())).thenReturn(false);

        assertThat(admission.admits(rule)).isFalse();
        verify(trail).changedGrantAdmission(AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED, rule);
        verify(sources, never()).authorsFilling(any());
    }

    /** A RESOURCE_MEMBER rule confers no authority, so the directory question does not arise for it. */
    @Test
    void aResourceMembershipRuleIsNotGatedOnItsDirectorySources() {
        MappingRule resourceRule = MappingRule.of(MappingTargetKind.RESOURCE_MEMBER, TARGET_ROLE, ORG, null);
        ReflectionTestUtils.setField(resourceRule, "id", UUID.randomUUID());

        assertThat(admission.admits(resourceRule)).isTrue();
        verify(sources, never()).authorsFilling(any());
    }

    private void directoryVouchedForBy(UUID configurator, boolean mayAssign) {
        when(sources.authorsFilling(any())).thenReturn(new AttributeSourceAuthors(Set.of(configurator), true));
        when(targetAuthority.authorMayAssign(eq(configurator), eq(MappingTargetKind.ROLE), eq(TARGET_ROLE)))
                .thenReturn(mayAssign);
    }

    private AttributeDefinition ownedBy(AttributeSource source) {
        return new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "department", "Department", null,
                AttributeDataType.STRING, List.of(), false, false, source, 0);
    }
}
