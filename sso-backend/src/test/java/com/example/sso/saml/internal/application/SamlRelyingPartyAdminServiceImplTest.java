package com.example.sso.saml.internal.application;

import com.example.sso.portal.ApplicationDeletedEvent;
import com.example.sso.saml.RelyingPartyRequest;
import com.example.sso.saml.RelyingPartyView;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SamlRelyingPartyAdminServiceImpl} admin CRUD. Focus: the entityId uniqueness
 * guard (409 on duplicate), the not-found guards on update/delete, and the side effect that matters to
 * the rest of the system — deleting an RP publishes an {@link ApplicationDeletedEvent} so downstream
 * modules (portal assignments) can react.
 */
@ExtendWith(MockitoExtension.class)
class SamlRelyingPartyAdminServiceImplTest {

    @Mock
    private SamlRelyingPartyRepository relyingParties;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private SamlRelyingPartyAdminServiceImpl service;

    private RelyingPartyRequest request(String entityId) {
        return new RelyingPartyRequest(entityId, "Acme SP", "https://sp.example/acs", null,
                true, false, false, null, null, null, false, true, null, null, null);
    }

    private SamlRelyingParty stubbedRp(UUID id, String entityId) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        when(rp.getId()).thenReturn(id);
        when(rp.getEntityId()).thenReturn(entityId);
        when(rp.getAcsUrl()).thenReturn("https://sp.example/acs");
        when(rp.getNameIdFormat()).thenReturn(SamlRelyingParty.NAMEID_EMAIL);
        when(rp.isSignAssertion()).thenReturn(true);
        when(rp.isSignResponse()).thenReturn(false);
        when(rp.isEncryptAssertion()).thenReturn(false);
        when(rp.getSignatureAlgorithm()).thenReturn("RSA_SHA256");
        when(rp.getDataEncryptionAlgorithm()).thenReturn("AES256_GCM");
        when(rp.getKeyTransportAlgorithm()).thenReturn("RSA_OAEP");
        when(rp.isWantAuthnRequestsSigned()).thenReturn(false);
        when(rp.isAllowIdpInitiated()).thenReturn(true);
        when(rp.getSigningCertificate()).thenReturn(null);
        when(rp.getEncryptionCertificate()).thenReturn(null);
        when(rp.getSpLoginUrl()).thenReturn(null);
        return rp;
    }

    @Test
    void createRejectsDuplicateEntityId() {
        when(relyingParties.existsByEntityId("dup")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("dup")))
                .isInstanceOf(ConflictException.class);
        verify(relyingParties, never()).save(any());
    }

    @Test
    void createPersistsAndReturnsView() {
        UUID id = UUID.randomUUID();
        when(relyingParties.existsByEntityId("new-sp")).thenReturn(false);
        SamlRelyingParty saved = stubbedRp(id, "new-sp");
        when(relyingParties.save(any())).thenReturn(saved);

        RelyingPartyView view = service.create(request("new-sp"));

        verify(relyingParties).save(any(SamlRelyingParty.class));
        assertThat(view.id()).isEqualTo(id.toString());
        assertThat(view.entityId()).isEqualTo("new-sp");
        assertThat(view.signatureAlgorithm()).isEqualTo("RSA_SHA256");
    }

    @Test
    void updateReturnsViewForExistingRelyingParty() {
        UUID id = UUID.randomUUID();
        SamlRelyingParty existing = stubbedRp(id, "edit-sp");
        when(relyingParties.findById(id)).thenReturn(Optional.of(existing));
        when(relyingParties.save(existing)).thenReturn(existing);

        RelyingPartyView view = service.update(id, request("ignored-on-update"));

        verify(existing).update(any(), any(), any(), any(), any(), any(), any());
        verify(relyingParties).save(existing);
        assertThat(view.entityId()).isEqualTo("edit-sp");
    }

    @Test
    void updateRejectsUnknownId() {
        UUID id = UUID.randomUUID();
        when(relyingParties.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, request("x")))
                .isInstanceOf(NotFoundException.class);
        verify(relyingParties, never()).save(any());
    }

    @Test
    void deletePublishesApplicationDeletedEvent() {
        UUID id = UUID.randomUUID();
        when(relyingParties.existsById(id)).thenReturn(true);

        service.delete(id);

        verify(relyingParties).deleteById(id);
        ArgumentCaptor<ApplicationDeletedEvent> event = ArgumentCaptor.forClass(ApplicationDeletedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().appId()).isEqualTo(id.toString());
    }

    @Test
    void deleteRejectsUnknownIdAndPublishesNothing() {
        UUID id = UUID.randomUUID();
        when(relyingParties.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(NotFoundException.class);
        verify(relyingParties, never()).deleteById(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void ensureRelyingPartyIsIdempotentWhenEntityIdExists() {
        when(relyingParties.existsByEntityId("known")).thenReturn(true);

        service.ensureRelyingParty("known", "https://sp.example/acs");

        verify(relyingParties, never()).save(any());
    }

    @Test
    void ensureRelyingPartyCreatesWhenAbsent() {
        when(relyingParties.existsByEntityId("fresh")).thenReturn(false);

        service.ensureRelyingParty("fresh", "https://sp.example/acs");

        verify(relyingParties).save(any(SamlRelyingParty.class));
    }
}
