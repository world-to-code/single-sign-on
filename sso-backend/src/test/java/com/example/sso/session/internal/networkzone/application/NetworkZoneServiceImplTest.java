package com.example.sso.session.internal.networkzone.application;


import com.example.sso.session.networkzone.NetworkZoneSpec;
import com.example.sso.session.networkzone.NetworkZoneView;
import com.example.sso.session.internal.networkzone.domain.NetworkZone;
import com.example.sso.session.internal.networkzone.domain.NetworkZoneCidr;
import com.example.sso.session.internal.networkzone.domain.NetworkZoneCidrRepository;
import com.example.sso.session.internal.networkzone.domain.NetworkZoneRepository;
import com.example.sso.session.internal.policy.domain.SessionPolicyIpRuleRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link NetworkZoneServiceImpl}: catalog CRUD (name-uniqueness, CIDR validation, at-least-one
 * CIDR, delete-blocked-if-referenced) and the {@code zoneId → CIDRs} cache used by enforcement. CIDRs are now
 * explicit {@link NetworkZoneCidr} rows, so the tests assert on the inserts/deletes the service issues.
 * Collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class NetworkZoneServiceImplTest {

    @Mock
    private NetworkZoneRepository repository;
    @Mock
    private NetworkZoneCidrRepository cidrs;
    @Mock
    private SessionPolicyIpRuleRepository policyIpRules;
    @Mock
    private OrgContext orgContext;
    @Mock
    private ApplicationEventPublisher events;

    private NetworkZoneServiceImpl service;

    @BeforeEach
    void setUp() {
        // Default to the platform context (no org bound → the global tier); the cache reload runs as platform.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        // Exercise the REAL tier guard (driven by the mocked OrgContext) so the isolation checks are genuine.
        service = new NetworkZoneServiceImpl(
                repository, cidrs, policyIpRules, orgContext, new OrgTierGuard(orgContext), events);
    }

    private NetworkZone zone(String name, String description) {
        NetworkZone z = new NetworkZone(name, description);
        ReflectionTestUtils.setField(z, "id", UUID.randomUUID());
        return z;
    }

    private NetworkZoneCidr cidrRow(UUID zoneId, String cidr) {
        return new NetworkZoneCidr(zoneId, cidr);
    }

    private void stampIdOnSave() {
        when(repository.save(any(NetworkZone.class))).thenAnswer(inv -> {
            NetworkZone z = inv.getArgument(0);
            if (z.getId() == null) {
                ReflectionTestUtils.setField(z, "id", UUID.randomUUID());
            }
            return z;
        });
    }

    // --- create ---

    @Test
    void createValidatesPersistsEachCidrAndRefreshesTheCache() {
        when(repository.findByNameAndOrgIdIsNull("Office")).thenReturn(Optional.empty());
        stampIdOnSave();

        NetworkZoneView view = service.create(new NetworkZoneSpec("Office", "HQ", List.of("192.168.0.0/16", "10.0.0.0/8")));

        assertThat(view.name()).isEqualTo("Office");
        assertThat(view.cidrs()).containsExactlyInAnyOrder("192.168.0.0/16", "10.0.0.0/8");
        verify(repository).save(any(NetworkZone.class));
        verify(cidrs, times(2)).save(any(NetworkZoneCidr.class)); // one row per CIDR
        verify(events).publishEvent(any(NetworkZoneCacheChanged.class)); // cache rebuilt AFTER_COMMIT
    }

    @Test
    void createTrimsAndDeduplicatesCidrs() {
        when(repository.findByNameAndOrgIdIsNull("Z")).thenReturn(Optional.empty());
        stampIdOnSave();

        NetworkZoneView view = service.create(new NetworkZoneSpec("Z", null, List.of("  10.0.0.0/8 ", "10.0.0.0/8", "")));

        assertThat(view.cidrs()).containsExactly("10.0.0.0/8");
        verify(cidrs, times(1)).save(any(NetworkZoneCidr.class));
    }

    @Test
    void createRejectsADuplicateName() {
        when(repository.findByNameAndOrgIdIsNull("Dup")).thenReturn(Optional.of(zone("Dup", null)));

        assertThatThrownBy(() -> service.create(new NetworkZoneSpec("Dup", null, List.of("10.0.0.0/8"))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
        verify(cidrs, never()).save(any());
    }

    @Test
    void createRejectsAnEmptyCidrList() {
        when(repository.findByNameAndOrgIdIsNull("Empty")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new NetworkZoneSpec("Empty", null, List.of())))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsAnInvalidCidr() {
        when(repository.findByNameAndOrgIdIsNull("Bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new NetworkZoneSpec("Bad", null, List.of("10.0.0.0/8", "nope"))))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    // --- update ---

    @Test
    void updateRenamesAndReplacesCidrsByDiff() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Old", "d");
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.findByNameAndOrgIdIsNull("New")).thenReturn(Optional.empty());
        when(repository.save(existing)).thenReturn(existing);
        // Current row 1.1.1.0/24 is dropped; 2.2.2.0/24 is added.
        when(cidrs.findByZoneId(id)).thenReturn(List.of(cidrRow(id, "1.1.1.0/24")));

        NetworkZoneView view = service.update(id, new NetworkZoneSpec("New", "d2", List.of("2.2.2.0/24")));

        assertThat(view.name()).isEqualTo("New");
        assertThat(view.cidrs()).containsExactly("2.2.2.0/24");
        ArgumentCaptor<NetworkZoneCidr> deleted = ArgumentCaptor.forClass(NetworkZoneCidr.class);
        verify(cidrs).delete(deleted.capture());
        assertThat(deleted.getValue().cidr()).isEqualTo("1.1.1.0/24");
        ArgumentCaptor<NetworkZoneCidr> inserted = ArgumentCaptor.forClass(NetworkZoneCidr.class);
        verify(cidrs).save(inserted.capture());
        assertThat(inserted.getValue().cidr()).isEqualTo("2.2.2.0/24");
        verify(events).publishEvent(any(NetworkZoneCacheChanged.class)); // cache rebuilt AFTER_COMMIT
    }

    @Test
    void updateKeepsUnchangedCidrsWithoutDeletingOrReinserting() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Same", null);
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.findByNameAndOrgIdIsNull("Same")).thenReturn(Optional.of(existing)); // resolves to itself → not a conflict
        when(repository.save(existing)).thenReturn(existing);
        when(cidrs.findByZoneId(id)).thenReturn(List.of(cidrRow(id, "1.1.1.0/24")));

        NetworkZoneView view = service.update(id, new NetworkZoneSpec("Same", null, List.of("1.1.1.0/24")));

        assertThat(view.cidrs()).containsExactly("1.1.1.0/24");
        verify(cidrs, never()).delete(any());
        verify(cidrs, never()).save(any());
    }

    @Test
    void updateRejectsRenamingOntoAnotherZone() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Old", null);
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.findByNameAndOrgIdIsNull("Taken")).thenReturn(Optional.of(zone("Taken", null)));

        assertThatThrownBy(() -> service.update(id, new NetworkZoneSpec("Taken", null, List.of("1.1.1.0/24"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateOfAMissingZoneThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new NetworkZoneSpec("X", null, List.of("1.1.1.0/24"))))
                .isInstanceOf(NotFoundException.class);
    }

    // --- delete ---

    @Test
    void deleteRemovesChildCidrsThenTheZoneAndRefreshes() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Z", null);
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(policyIpRules.countByZoneId(id)).thenReturn(0L);

        service.delete(id);

        verify(cidrs).deleteByZoneId(id); // child rows removed explicitly before the owner
        verify(repository).delete(existing);
        verify(events).publishEvent(any(NetworkZoneCacheChanged.class));
    }

    @Test
    void deleteIsBlockedWhenAPolicyReferencesTheZone() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Z", null);
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(policyIpRules.countByZoneId(id)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConflictException.class);
        verify(cidrs, never()).deleteByZoneId(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteOfAMissingZoneThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRacingANewReferenceMapsTheFkViolationTo409NotA500() {
        // TOCTOU: a policy references the zone between countByZoneId and delete. The DB FK (RESTRICT)
        // rejects the delete — surface that as the same Conflict the guard would have raised, not a 500.
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Z", null);
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(policyIpRules.countByZoneId(id)).thenReturn(0L); // guard passes...
        doThrow(new DataIntegrityViolationException("fk_violation"))
                .when(repository).delete(existing);            // ...but the FK still refuses

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConflictException.class);
    }

    // --- resolution (cache) ---

    @Test
    void cidrsForZoneServesFromTheCacheAndIsEmptyForAnUnknownZone() {
        UUID office = UUID.randomUUID();
        when(cidrs.findAll()).thenReturn(List.of(cidrRow(office, "10.0.0.0/8"), cidrRow(office, "192.168.0.0/16")));
        service.load(); // @PostConstruct populates the volatile cache

        assertThat(service.cidrsForZone(office)).containsExactlyInAnyOrder("10.0.0.0/8", "192.168.0.0/16");
        assertThat(service.cidrsForZone(UUID.randomUUID())).isEmpty();
    }

    @Test
    void existsDelegatesToTheRepository() {
        UUID id = UUID.randomUUID();
        when(repository.existsById(id)).thenReturn(true);

        assertThat(service.exists(id)).isTrue();
    }

    // --- adversarial: tenant/platform tier isolation -----------------------------------------------

    @Test
    void createStampsTheZoneWithTheBoundTenantAsOwner() {
        UUID orgA = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findByNameAndOrgId("Office", orgA)).thenReturn(Optional.empty());
        stampIdOnSave();
        ArgumentCaptor<NetworkZone> captor = ArgumentCaptor.forClass(NetworkZone.class);

        service.create(new NetworkZoneSpec("Office", null, List.of("10.0.0.0/8")));

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOrgId()).isEqualTo(orgA); // never a global (org_id null) zone
    }

    @Test
    void aTenantAdminCannotUpdateAGlobalZone() {
        UUID orgA = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(zone("Global", null))); // org_id null

        assertThatThrownBy(() -> service.update(id, new NetworkZoneSpec("X", null, List.of("2.2.2.0/24"))))
                .isInstanceOf(NotFoundException.class); // 404 — no enumeration of a global zone
        verify(repository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotDeleteAnotherTenantsZone() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(repository.findById(id)).thenReturn(Optional.of(orgZone("B-Zone", orgB)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void thePlatformAdminCannotMutateAnOrgZoneWithoutDrillingIn() {
        UUID orgB = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        // Platform context (no org bound) — the @BeforeEach default — must not reach a tenant's zone.
        when(repository.findById(id)).thenReturn(Optional.of(orgZone("B-Zone", orgB)));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(repository, never()).delete(any());
    }

    private NetworkZone orgZone(String name, UUID orgId) {
        NetworkZone z = new NetworkZone(name, null, orgId);
        ReflectionTestUtils.setField(z, "id", UUID.randomUUID());
        return z;
    }
}
