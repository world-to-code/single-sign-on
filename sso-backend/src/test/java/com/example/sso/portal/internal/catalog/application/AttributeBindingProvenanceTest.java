package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Who is allowed to decide that a user matches a POLICY binding.
 *
 * <p>An attribute binding selects the auth and session policy that governs someone, and the session layer
 * re-resolves it on every request — so writing an attribute changes a live security posture. Auto-mapping
 * already refuses to let an unattributable source drive a role grant; without the same question here, a
 * machine credential that can write a directory-owned attribute could relax a live session's
 * re-authentication interval, its factors or its client binding, and shorten the next login's MFA, with no
 * step-up anywhere.
 */
@ExtendWith(MockitoExtension.class)
class AttributeBindingProvenanceTest {

    private static final UUID CONFIGURATOR = UUID.randomUUID();

    @Mock private PolicyBindingRepository bindings;
    @Mock private PolicyBindingConditions conditionGroups;
    @Mock private UserGroupService userGroups;
    @Mock private AuthPolicyResolver authPolicies;
    @Mock private SessionPolicyService sessionPolicies;
    @Mock private AttributeService attributes;
    @Mock private AttributeDefinitionService definitions;
    @Mock private AttributeSourceAuthority sources;

    private PolicyBindingResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new PolicyBindingResolverImpl(bindings, conditionGroups, userGroups, authPolicies,
                sessionPolicies, attributes, definitions, sources);
    }

    private AttributePredicateGroup group() {
        return AttributePredicateGroup.of(
                new AttributePredicate("department", AttributeOperator.EQUALS, "IT", List.of()));
    }

    private void ownedBy(AttributeSource source) {
        lenient().when(definitions.definitionOf(eq(EntityKind.USER), anyString())).thenReturn(Optional.of(
                new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, "department", "Department", null,
                        AttributeDataType.STRING, List.of(), false, false, source, 0)));
    }

    private boolean accountedFor() {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(resolver, "sourcesAccountedFor", group()));
    }

    /** A directory whose configurator is on record can drive a policy — the legitimate configuration. */
    @Test
    void anAttributedSourceMayDriveAPolicyBinding() {
        ownedBy(AttributeSource.DIRECTORY);
        when(sources.authorsFilling(any()))
                .thenReturn(new AttributeSourceAuthors(Set.of(CONFIGURATOR), true));

        assertThat(accountedFor()).isTrue();
    }

    /**
     * SCIM and CSV push to us and have nobody to attribute. A machine credential must not be able to select
     * the policy that governs a live session.
     */
    @Test
    void anUnattributableSourceMayNotDriveAPolicyBinding() {
        ownedBy(AttributeSource.DIRECTORY);
        when(sources.authorsFilling(any()))
                .thenReturn(new AttributeSourceAuthors(Set.of(CONFIGURATOR), false));

        assertThat(accountedFor()).isFalse();
    }

    /** Nothing fills the key at all — no source vouches for it, so it cannot select a policy either. */
    @Test
    void aKeyNoSourceFillsMayNotDriveAPolicyBinding() {
        ownedBy(AttributeSource.DIRECTORY);
        when(sources.authorsFilling(any())).thenReturn(AttributeSourceAuthors.none());

        assertThat(accountedFor()).isFalse();
    }

    /**
     * A locally-owned key is written through the audited admin path by someone who already holds the
     * authority, so it is not the attacker's lever and must keep working without any source at all.
     */
    @Test
    void aLocallyOwnedKeyNeedsNoSourceToVouchForIt() {
        ownedBy(AttributeSource.LOCAL);

        assertThat(accountedFor()).isTrue();
    }

    /** A key nobody declared is likewise not source-filled — the sync refuses undeclared keys. */
    @Test
    void anUndeclaredKeyNeedsNoSourceToVouchForIt() {
        lenient().when(definitions.definitionOf(eq(EntityKind.USER), anyString()))
                .thenReturn(Optional.empty());

        assertThat(accountedFor()).isTrue();
    }
}
