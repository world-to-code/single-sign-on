package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.AttributeSourceAuthority;
import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.AttributeSourceConfigurationChangedEvent;
import com.example.sso.metadata.EntityKind;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The verdict "can the sources filling these keys be attributed to anyone" is a property of the tenant's
 * configuration, not of the user being resolved — but it was being asked once per binding per request, on the
 * path that decides a live session's policy. Roughly ten queries per authenticated request for a three-condition
 * binding, against the ABAC hot table.
 *
 * <p>It is cached here, which also rate-limits the warning: a binding that silently stops applying is otherwise
 * invisible, and one log line per request is not an option on this path.
 */
@ExtendWith(MockitoExtension.class)
class AttributeSourceProvenanceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();

    @Mock private AttributeDefinitionService definitions;
    @Mock private AttributeSourceAuthority sources;
    @Mock private OrgContext orgContext;

    private AttributeSourceProvenance provenance;

    @BeforeEach
    void setUp() {
        provenance = new AttributeSourceProvenance(definitions, sources, orgContext, Duration.ofMinutes(1), 1000);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
    }

    private void ownedBy(String key, AttributeSource source) {
        lenient().when(definitions.definitionOf(EntityKind.USER, key)).thenReturn(Optional.of(
                new AttributeDefinition(UUID.randomUUID(), EntityKind.USER, key, key, null,
                        AttributeDataType.STRING, List.of(), false, false, source, 0)));
    }

    /** Nobody but an administrator can write these, so there is no source to vouch for. */
    @Test
    void keysAnAdministratorOwnsNeedNoVouching() {
        ownedBy("team", AttributeSource.LOCAL);

        assertThat(provenance.accountedFor(Set.of("team"))).isTrue();

        verify(sources, never()).authorsFilling(any());
    }

    @Test
    void aSourceThatCannotBeAttributedIsNotAccountedFor() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance")))
                .thenReturn(new AttributeSourceAuthors(Set.of(), false));

        assertThat(provenance.accountedFor(Set.of("clearance"))).isFalse();
    }

    @Test
    void anAttributedSourceIsAccountedFor() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance")))
                .thenReturn(new AttributeSourceAuthors(Set.of(UUID.randomUUID()), true));

        assertThat(provenance.accountedFor(Set.of("clearance"))).isTrue();
    }

    /** Declared as source-owned, but nothing actually fills it — nobody vouches, so it cannot select a policy. */
    @Test
    void aKeyNoSourceFillsIsNotAccountedFor() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance"))).thenReturn(AttributeSourceAuthors.none());

        assertThat(provenance.accountedFor(Set.of("clearance"))).isFalse();
    }

    /** A key nobody declared is not source-filled either — the sync refuses undeclared keys. */
    @Test
    void anUndeclaredKeyNeedsNoVouching() {
        when(definitions.definitionOf(EntityKind.USER, "stray")).thenReturn(Optional.empty());

        assertThat(provenance.accountedFor(Set.of("stray"))).isTrue();

        verify(sources, never()).authorsFilling(any());
    }

    /** The point of the cache: the same question on the next request costs nothing. */
    @Test
    void theVerdictIsComputedOncePerKeySet() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance")))
                .thenReturn(new AttributeSourceAuthors(Set.of(UUID.randomUUID()), true));

        provenance.accountedFor(Set.of("clearance"));
        provenance.accountedFor(Set.of("clearance"));

        verify(sources, times(1)).authorsFilling(Set.of("clearance"));
        verify(definitions, times(1)).definitionOf(EntityKind.USER, "clearance");
    }

    /**
     * A cached verdict must not leak across tenants. The key set is identical between organizations — attribute
     * keys are tenant-chosen names, and two tenants naming a key {@code clearance} is the expected case, not an
     * edge one. Caching on the keys alone would let one tenant's connector decide another tenant's policy.
     */
    @Test
    void twoOrganizationsDoNotShareAVerdict() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance")))
                .thenReturn(new AttributeSourceAuthors(Set.of(UUID.randomUUID()), true))
                .thenReturn(new AttributeSourceAuthors(Set.of(), false));

        assertThat(provenance.accountedFor(Set.of("clearance"))).isTrue();
        when(orgContext.currentOrg()).thenReturn(Optional.of(OTHER_ORG));

        assertThat(provenance.accountedFor(Set.of("clearance"))).isFalse();
    }

    /** Order is not part of the question, so two spellings of one key set must not cost two computations. */
    @Test
    void theKeySetIsOrderIndependent() {
        ownedBy("a", AttributeSource.DIRECTORY);
        ownedBy("b", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(any()))
                .thenReturn(new AttributeSourceAuthors(Set.of(UUID.randomUUID()), true));

        provenance.accountedFor(new LinkedHashSet<>(List.of("a", "b")));
        provenance.accountedFor(new LinkedHashSet<>(List.of("b", "a")));

        verify(sources, times(1)).authorsFilling(any());
    }
    /**
     * A cached verdict must not outlive the change that revokes it. An administrator who deletes a compromised
     * connector is revoking what it vouched for; if the positive verdict survives, the binding that source fed
     * keeps selecting a policy until the entry expires — a revocation that did not propagate.
     */
    @Test
    void aConfigurationChangeDropsThatOrganizationsVerdicts() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance")))
                .thenReturn(new AttributeSourceAuthors(Set.of(UUID.randomUUID()), true))
                .thenReturn(new AttributeSourceAuthors(Set.of(), false));

        assertThat(provenance.accountedFor(Set.of("clearance"))).isTrue();
        provenance.onSourceConfigurationChanged(new AttributeSourceConfigurationChangedEvent(ORG));

        assertThat(provenance.accountedFor(Set.of("clearance"))).isFalse();
    }

    /** Another tenant's change is not a reason to recompute ours. */
    @Test
    void anotherOrganizationsChangeLeavesOurVerdictAlone() {
        ownedBy("clearance", AttributeSource.DIRECTORY);
        when(sources.authorsFilling(Set.of("clearance")))
                .thenReturn(new AttributeSourceAuthors(Set.of(UUID.randomUUID()), true));

        provenance.accountedFor(Set.of("clearance"));
        provenance.onSourceConfigurationChanged(new AttributeSourceConfigurationChangedEvent(OTHER_ORG));
        provenance.accountedFor(Set.of("clearance"));

        verify(sources, times(1)).authorsFilling(Set.of("clearance"));
    }
}
