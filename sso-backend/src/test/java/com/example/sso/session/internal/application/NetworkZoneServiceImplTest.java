package com.example.sso.session.internal.application;

import com.example.sso.session.NetworkZoneSpec;
import com.example.sso.session.NetworkZoneView;
import com.example.sso.session.internal.domain.NetworkZone;
import com.example.sso.session.internal.domain.NetworkZoneRepository;
import com.example.sso.session.internal.domain.SessionPolicyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link NetworkZoneServiceImpl}: catalog CRUD (name-uniqueness, CIDR validation, at-least-one
 * CIDR, delete-blocked-if-referenced) and the {@code zoneId → CIDRs} cache used by enforcement. Real
 * {@link NetworkZone} entities back the writes (id stamped like the DB would); collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
class NetworkZoneServiceImplTest {

    @Mock
    private NetworkZoneRepository repository;
    @Mock
    private SessionPolicyRepository policies;
    @Mock
    private OrgContext orgContext;
    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private NetworkZoneServiceImpl service;

    @BeforeEach
    void setUp() {
        // Default to the platform context (no org bound → the global tier); the cache reload runs as platform.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    }

    private NetworkZone zone(String name, String description, List<String> cidrs) {
        NetworkZone z = new NetworkZone(name, description, cidrs);
        ReflectionTestUtils.setField(z, "id", UUID.randomUUID());
        return z;
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
    void createValidatesPersistsAndRefreshesTheCache() {
        when(repository.findByNameAndOrgIdIsNull("Office")).thenReturn(Optional.empty());
        stampIdOnSave();

        NetworkZoneView view = service.create(new NetworkZoneSpec("Office", "HQ", List.of("192.168.0.0/16", "10.0.0.0/8")));

        assertThat(view.name()).isEqualTo("Office");
        assertThat(view.cidrs()).containsExactlyInAnyOrder("192.168.0.0/16", "10.0.0.0/8");
        verify(repository).save(any(NetworkZone.class));
        verify(events).publishEvent(any(NetworkZoneCacheChanged.class)); // cache rebuilt AFTER_COMMIT
    }

    @Test
    void createTrimsAndDeduplicatesCidrs() {
        when(repository.findByNameAndOrgIdIsNull("Z")).thenReturn(Optional.empty());
        stampIdOnSave();
        ArgumentCaptor<NetworkZone> captor = ArgumentCaptor.forClass(NetworkZone.class);

        service.create(new NetworkZoneSpec("Z", null, List.of("  10.0.0.0/8 ", "10.0.0.0/8", "")));

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().cidrList()).containsExactly("10.0.0.0/8");
    }

    @Test
    void createRejectsADuplicateName() {
        when(repository.findByNameAndOrgIdIsNull("Dup")).thenReturn(Optional.of(zone("Dup", null, List.of("10.0.0.0/8"))));

        assertThatThrownBy(() -> service.create(new NetworkZoneSpec("Dup", null, List.of("10.0.0.0/8"))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
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
    void updateRenamesAndReplacesCidrs() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Old", "d", List.of("1.1.1.0/24"));
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.findByNameAndOrgIdIsNull("New")).thenReturn(Optional.empty());
        when(repository.save(existing)).thenReturn(existing);

        NetworkZoneView view = service.update(id, new NetworkZoneSpec("New", "d2", List.of("2.2.2.0/24")));

        assertThat(view.name()).isEqualTo("New");
        assertThat(view.cidrs()).containsExactly("2.2.2.0/24");
        verify(events).publishEvent(any(NetworkZoneCacheChanged.class));
    }

    @Test
    void updateAllowsKeepingItsOwnName() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Same", null, List.of("1.1.1.0/24"));
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.findByNameAndOrgIdIsNull("Same")).thenReturn(Optional.of(existing)); // resolves to itself → not a conflict
        when(repository.save(existing)).thenReturn(existing);

        NetworkZoneView view = service.update(id, new NetworkZoneSpec("Same", null, List.of("3.3.3.0/24")));

        assertThat(view.cidrs()).containsExactly("3.3.3.0/24");
    }

    @Test
    void updateRejectsRenamingOntoAnotherZone() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Old", null, List.of("1.1.1.0/24"));
        ReflectionTestUtils.setField(existing, "id", id);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.findByNameAndOrgIdIsNull("Taken")).thenReturn(Optional.of(zone("Taken", null, List.of("2.2.2.0/24"))));

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
    void deleteRemovesAnUnreferencedZoneAndRefreshes() {
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Z", null, List.of("1.1.1.0/24"));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(policies.countReferencingZone(id)).thenReturn(0L);

        service.delete(id);

        verify(repository).delete(existing);
        verify(events).publishEvent(any(NetworkZoneCacheChanged.class));
    }

    @Test
    void deleteIsBlockedWhenAPolicyReferencesTheZone() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(zone("Z", null, List.of("1.1.1.0/24"))));
        when(policies.countReferencingZone(id)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConflictException.class);
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
        // TOCTOU: a policy references the zone between countReferencingZone and delete. The DB FK (RESTRICT)
        // rejects the delete — surface that as the same Conflict the guard would have raised, not a 500.
        UUID id = UUID.randomUUID();
        NetworkZone existing = zone("Z", null, List.of("1.1.1.0/24"));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(policies.countReferencingZone(id)).thenReturn(0L); // guard passes...
        doThrow(new DataIntegrityViolationException("fk_violation"))
                .when(repository).delete(existing);              // ...but the FK still refuses

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConflictException.class);
    }

    // --- resolution (cache) ---

    @Test
    void cidrsForZoneServesFromTheCacheAndIsEmptyForAnUnknownZone() {
        NetworkZone office = zone("Office", null, List.of("10.0.0.0/8", "192.168.0.0/16"));
        when(repository.findAllWithCidrs()).thenReturn(List.of(office));
        service.load(); // @PostConstruct populates the volatile cache

        assertThat(service.cidrsForZone(office.getId())).containsExactlyInAnyOrder("10.0.0.0/8", "192.168.0.0/16");
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
        when(repository.findById(id)).thenReturn(Optional.of(zone("Global", null, List.of("1.1.1.0/24")))); // org_id null

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
        NetworkZone z = new NetworkZone(name, null, List.of("9.9.9.0/24"), orgId);
        ReflectionTestUtils.setField(z, "id", UUID.randomUUID());
        return z;
    }
}
