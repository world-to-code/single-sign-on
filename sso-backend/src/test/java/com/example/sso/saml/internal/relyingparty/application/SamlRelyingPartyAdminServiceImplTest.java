package com.example.sso.saml.internal.relyingparty.application;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;

import com.example.sso.portal.application.ApplicationDeletedEvent;
import com.example.sso.saml.relyingparty.RelyingPartyRequest;
import com.example.sso.saml.relyingparty.RelyingPartyView;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SamlRelyingPartyAdminServiceImpl} admin CRUD, including the org-tier isolation the
 * service enforces on top of RLS. Uses a REAL {@link OrgTierGuard} over a mocked {@link OrgContext} so the
 * ownership logic is exercised, not stubbed. Focus: the globally-unique entityId guard runs cross-org
 * (409 on duplicate), create stamps the caller's tier onto the RP, update/delete refuse a row outside the
 * caller's tier with a non-revealing 404, list is scoped to the caller's tier, and deleting an in-tier RP
 * publishes an {@link ApplicationDeletedEvent} so downstream modules (portal assignments) can react.
 */
class SamlRelyingPartyAdminServiceImplTest {

    private final SamlRelyingPartyRepository relyingParties = mock(SamlRelyingPartyRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrgContext orgContext = mock(OrgContext.class);

    private SamlRelyingPartyAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty()); // platform tier by default
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        service = new SamlRelyingPartyAdminServiceImpl(relyingParties, events,
                orgContext, new OrgTierGuard(orgContext));
    }

    private RelyingPartyRequest request(String entityId) {
        return new RelyingPartyRequest(entityId, "Acme SP", "https://sp.example/acs", null,
                true, false, false, null, null, null, false, true, null, null, null, null, null);
    }

    private SamlRelyingParty stubbedRp(UUID id, String entityId, UUID orgId) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        lenient().when(rp.getId()).thenReturn(id);
        lenient().when(rp.getOrgId()).thenReturn(orgId);
        lenient().when(rp.getEntityId()).thenReturn(entityId);
        lenient().when(rp.getAcsUrl()).thenReturn("https://sp.example/acs");
        lenient().when(rp.getNameIdFormat()).thenReturn(SamlRelyingParty.NAMEID_EMAIL);
        lenient().when(rp.isSignAssertion()).thenReturn(true);
        lenient().when(rp.isSignResponse()).thenReturn(false);
        lenient().when(rp.isEncryptAssertion()).thenReturn(false);
        lenient().when(rp.getSignatureAlgorithm()).thenReturn("RSA_SHA256");
        lenient().when(rp.getDataEncryptionAlgorithm()).thenReturn("AES256_GCM");
        lenient().when(rp.getKeyTransportAlgorithm()).thenReturn("RSA_OAEP");
        lenient().when(rp.isWantAuthnRequestsSigned()).thenReturn(false);
        lenient().when(rp.isAllowIdpInitiated()).thenReturn(true);
        return rp;
    }

    @Test
    void createRejectsDuplicateEntityIdCheckedCrossOrg() {
        when(relyingParties.existsByEntityId("dup")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("dup")))
                .isInstanceOf(ConflictException.class);
        // the uniqueness check must run in platform context so another tenant's RP is not hidden by RLS
        verify(orgContext).callAsPlatform(any());
        verify(relyingParties, never()).save(any());
    }

    @Test
    void createPersistsAndReturnsView() {
        UUID id = UUID.randomUUID();
        SamlRelyingParty saved = stubbedRp(id, "new-sp", null);
        when(relyingParties.existsByEntityId("new-sp")).thenReturn(false);
        when(relyingParties.save(any())).thenReturn(saved);

        RelyingPartyView view = service.create(request("new-sp"));

        verify(relyingParties).save(any(SamlRelyingParty.class));
        assertThat(view.id()).isEqualTo(id.toString());
        assertThat(view.entityId()).isEqualTo("new-sp");
        assertThat(view.signatureAlgorithm()).isEqualTo("RSA_SHA256");
    }

    @Test
    void createStampsTheCallersTierOntoTheRelyingParty() {
        UUID orgA = UUID.randomUUID();
        SamlRelyingParty persisted = stubbedRp(UUID.randomUUID(), "tenant-sp", orgA);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(relyingParties.existsByEntityId("tenant-sp")).thenReturn(false);
        when(relyingParties.save(any())).thenReturn(persisted);

        service.create(request("tenant-sp"));

        ArgumentCaptor<SamlRelyingParty> saved = ArgumentCaptor.forClass(SamlRelyingParty.class);
        verify(relyingParties).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(orgA); // stamped with the caller's tier, not persisted stub
    }

    @Test
    void updateReturnsViewForRelyingPartyInTier() {
        UUID id = UUID.randomUUID();
        SamlRelyingParty existing = stubbedRp(id, "edit-sp", null); // global RP, platform tier
        when(relyingParties.findById(id)).thenReturn(Optional.of(existing));
        when(relyingParties.save(existing)).thenReturn(existing);

        RelyingPartyView view = service.update(id, request("ignored-on-update"));

        verify(existing).update(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
    void updateRefusesAnotherTenantsRelyingPartyAsNotFound() {
        UUID id = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        SamlRelyingParty bRp = stubbedRp(id, "b-sp", orgB);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(relyingParties.findById(id)).thenReturn(Optional.of(bRp));

        assertThatThrownBy(() -> service.update(id, request("b-sp")))
                .isInstanceOf(NotFoundException.class);
        verify(relyingParties, never()).save(any());
    }

    @Test
    void deletePublishesApplicationDeletedEvent() {
        UUID id = UUID.randomUUID();
        SamlRelyingParty rp = stubbedRp(id, "gone-sp", null);
        when(relyingParties.findById(id)).thenReturn(Optional.of(rp));

        service.delete(id);

        verify(relyingParties).delete(rp);
        ArgumentCaptor<ApplicationDeletedEvent> event = ArgumentCaptor.forClass(ApplicationDeletedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().appId()).isEqualTo(id.toString());
    }

    @Test
    void deleteRejectsUnknownIdAndPublishesNothing() {
        UUID id = UUID.randomUUID();
        when(relyingParties.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(NotFoundException.class);
        verify(relyingParties, never()).delete(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void deleteRefusesAnotherTenantsRelyingPartyAsNotFound() {
        UUID id = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        SamlRelyingParty bRp = stubbedRp(id, "b-sp", orgB);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(relyingParties.findById(id)).thenReturn(Optional.of(bRp));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(NotFoundException.class);
        verify(relyingParties, never()).delete(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void listReturnsGlobalRelyingPartiesForThePlatformTier() {
        UUID id = UUID.randomUUID();
        SamlRelyingParty global = stubbedRp(id, "global-sp", null);
        when(relyingParties.findAllByOrgIdIsNull()).thenReturn(List.of(global));

        assertThat(service.list(0, 20).items()).singleElement()
                .satisfies(v -> assertThat(v.entityId()).isEqualTo("global-sp"));
        verify(relyingParties, never()).findAllByOrgId(any());
    }

    @Test
    void listReturnsOnlyTheBoundTenantsRelyingParties() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        SamlRelyingParty aRp = stubbedRp(id, "a-sp", orgA);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(relyingParties.findAllByOrgId(orgA)).thenReturn(List.of(aRp));

        assertThat(service.list(0, 20).items()).singleElement()
                .satisfies(v -> assertThat(v.entityId()).isEqualTo("a-sp"));
        verify(relyingParties, never()).findAllByOrgIdIsNull();
    }

    @Test
    void ensureRelyingPartyIsIdempotentWhenEntityIdExists() {
        when(relyingParties.existsByEntityId("known")).thenReturn(true);

        service.ensureRelyingParty("known", "https://sp.example/acs");

        verify(relyingParties, never()).save(any());
    }

    @Test
    void ensureRelyingPartyCreatesGlobalRelyingPartyWhenAbsent() {
        when(relyingParties.existsByEntityId("fresh")).thenReturn(false);

        service.ensureRelyingParty("fresh", "https://sp.example/acs");

        ArgumentCaptor<SamlRelyingParty> saved = ArgumentCaptor.forClass(SamlRelyingParty.class);
        verify(relyingParties).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isNull(); // seeded RPs are platform-wide
    }
}
