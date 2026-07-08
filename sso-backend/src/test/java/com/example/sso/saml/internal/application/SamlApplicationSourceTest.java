package com.example.sso.saml.internal.application;

import com.example.sso.portal.ApplicationDescriptor;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The launchable-app name of a SAML relying party is its admin-set display name, falling back to the
 * (URN-like) entityId when none is set — so the portal/admin lists show a friendly label. The catalog is
 * scoped to the acting tier (symmetric with the OIDC source): a tenant sees only its own org's RPs.
 */
class SamlApplicationSourceTest {

    private final SamlRelyingPartyRepository relyingParties = mock(SamlRelyingPartyRepository.class);
    private final OrgTierGuard tierGuard = mock(OrgTierGuard.class);
    private final SamlApplicationSource source = new SamlApplicationSource(relyingParties, tierGuard);

    private SamlRelyingParty rp(String entityId, String displayName) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        lenient().when(rp.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rp.getEntityId()).thenReturn(entityId);
        lenient().when(rp.getDisplayName()).thenReturn(displayName);
        lenient().when(rp.getSpLoginUrl()).thenReturn(null);
        return rp;
    }

    /** Platform tier (no org bound): the source reads the GLOBAL relying parties. */
    private String nameOf(SamlRelyingParty rp) {
        when(tierGuard.currentTier()).thenReturn(null);
        when(relyingParties.findAllByOrgIdIsNull()).thenReturn(List.of(rp));
        return source.applications().get(0).name();
    }

    @Test
    void usesTheDisplayNameWhenSet() {
        assertThat(nameOf(rp("urn:sp:acme", "Acme Portal"))).isEqualTo("Acme Portal");
    }

    @Test
    void fallsBackToTheEntityIdWhenNoDisplayName() {
        assertThat(nameOf(rp("urn:sp:acme", null))).isEqualTo("urn:sp:acme");
        assertThat(nameOf(rp("urn:sp:acme", "  "))).isEqualTo("urn:sp:acme");
    }

    @Test
    void projectsEachRelyingPartyAsASamlApplication() {
        SamlRelyingParty rp = rp("urn:sp:x", "X");
        when(tierGuard.currentTier()).thenReturn(null);
        when(relyingParties.findAllByOrgIdIsNull()).thenReturn(List.of(rp));
        ApplicationDescriptor app = source.applications().get(0);
        assertThat(app.type().name()).isEqualTo("SAML");
        assertThat(app.name()).isEqualTo("X");
    }

    @Test
    void aTenantTierReadsOnlyItsOwnOrgsRelyingPartiesNeverTheGlobalFixtures() {
        UUID org = UUID.randomUUID();
        SamlRelyingParty tenantRp = rp("urn:sp:tenant", "Tenant SP");
        when(tierGuard.currentTier()).thenReturn(org);
        when(relyingParties.findAllByOrgId(org)).thenReturn(List.of(tenantRp));

        assertThat(source.applications()).singleElement()
                .extracting(ApplicationDescriptor::name).isEqualTo("Tenant SP");
        verify(relyingParties, never()).findAllByOrgIdIsNull(); // a tenant never sees the global RPs
        verify(relyingParties, never()).findAll();
    }
}
