package com.example.sso.saml.internal.application;

import com.example.sso.portal.ApplicationDescriptor;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The launchable-app name of a SAML relying party is its admin-set display name, falling back to the
 * (URN-like) entityId when none is set — so the portal/admin lists show a friendly label.
 */
class SamlApplicationSourceTest {

    private final SamlRelyingPartyRepository relyingParties = mock(SamlRelyingPartyRepository.class);
    private final SamlApplicationSource source = new SamlApplicationSource(relyingParties);

    private SamlRelyingParty rp(String entityId, String displayName) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        lenient().when(rp.getId()).thenReturn(UUID.randomUUID());
        lenient().when(rp.getEntityId()).thenReturn(entityId);
        lenient().when(rp.getDisplayName()).thenReturn(displayName);
        lenient().when(rp.getSpLoginUrl()).thenReturn(null);
        return rp;
    }

    private String nameOf(SamlRelyingParty rp) {
        when(relyingParties.findAll()).thenReturn(List.of(rp));
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
        when(relyingParties.findAll()).thenReturn(List.of(rp));
        ApplicationDescriptor app = source.applications().get(0);
        assertThat(app.type().name()).isEqualTo("SAML");
        assertThat(app.name()).isEqualTo("X");
    }
}
