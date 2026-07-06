package com.example.sso.organization.internal.application;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationMembershipChangedEvent;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.organization.internal.domain.Organization;
import com.example.sso.organization.internal.domain.OrganizationMembership;
import com.example.sso.organization.internal.domain.OrganizationMembershipRepository;
import com.example.sso.organization.internal.domain.OrganizationRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrganizationServiceImpl}: slug normalization/validation, uniqueness, the
 * not-found paths, membership idempotency, and the membership-changed event on removal.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

    @Mock private OrganizationRepository organizations;
    @Mock private OrganizationMembershipRepository memberships;
    @Mock private ApplicationEventPublisher events;
    @Mock private UserService users;
    // A real OrgContext (not a mock) so runInOrg/callAsPlatform actually execute the wrapped action. No
    // active transaction in a unit test, so the connection binder is never consulted (raw no-op provider).
    @SuppressWarnings("unchecked")
    @Spy private OrgContext orgContext = new OrgContext(mock(ObjectProvider.class));

    @InjectMocks private OrganizationServiceImpl service;

    @Test
    void createNormalizesTheSlugToLowercaseAndPersists() {
        when(organizations.existsBySlug("acme")).thenReturn(false);
        when(organizations.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

        OrganizationView view = service.create(new NewOrganization("  ACME  ", "Acme Inc"));

        assertThat(view.slug()).isEqualTo("acme");
        assertThat(view.name()).isEqualTo("Acme Inc");
        assertThat(view.status()).isEqualTo(OrganizationStatus.ACTIVE);
    }

    @Test
    void createRejectsADuplicateSlug() {
        when(organizations.existsBySlug("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new NewOrganization("acme", "Acme")))
                .isInstanceOf(ConflictException.class);
        verify(organizations, never()).save(any());
    }

    @Test
    void createRejectsAMalformedSlug() {
        assertThatThrownBy(() -> service.create(new NewOrganization("Acme Corp!", "Acme")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create(new NewOrganization("a", "Acme"))) // too short
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createRejectsABlankName() {
        when(organizations.existsBySlug("acme")).thenReturn(false);

        assertThatThrownBy(() -> service.create(new NewOrganization("acme", "  ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateRenamesAndChangesStatus() {
        UUID id = UUID.randomUUID();
        Organization org = new Organization("acme", "Acme");
        when(organizations.findById(id)).thenReturn(Optional.of(org));
        when(memberships.findUserIdsByOrgId(id)).thenReturn(List.of()); // suspend fan-out over (no) members

        OrganizationView view = service.update(id, "Acme Renamed", OrganizationStatus.SUSPENDED);

        assertThat(view.name()).isEqualTo("Acme Renamed");
        assertThat(view.status()).isEqualTo(OrganizationStatus.SUSPENDED);
    }

    @Test
    void suspendingAnOrgFansOutAMembershipChangedEventPerMember() {
        // Suspension must end every member's org-bound session, so it publishes one membership-changed event
        // per member (the session module terminates on it). Activating/renaming publishes nothing.
        UUID id = UUID.randomUUID();
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        when(organizations.findById(id)).thenReturn(Optional.of(new Organization("acme", "Acme")));
        when(memberships.findUserIdsByOrgId(id)).thenReturn(List.of(memberA, memberB));

        service.update(id, "Acme", OrganizationStatus.SUSPENDED);

        ArgumentCaptor<OrganizationMembershipChangedEvent> event =
                ArgumentCaptor.forClass(OrganizationMembershipChangedEvent.class);
        verify(events, times(2)).publishEvent(event.capture());
        assertThat(event.getAllValues())
                .extracting(OrganizationMembershipChangedEvent::orgId, OrganizationMembershipChangedEvent::userId)
                .containsExactlyInAnyOrder(tuple(id, memberA), tuple(id, memberB));
    }

    @Test
    void activatingAnOrgPublishesNoTerminationEvents() {
        UUID id = UUID.randomUUID();
        when(organizations.findById(id)).thenReturn(Optional.of(new Organization("acme", "Acme")));

        service.update(id, "Acme", OrganizationStatus.ACTIVE);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void updateOfAMissingOrgThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(organizations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, "x", OrganizationStatus.ACTIVE))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteOfAMissingOrgThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(organizations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(organizations, never()).delete(any());
    }

    @Test
    void addMemberPersistsWhenNotAlreadyAMember() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(organizations.findById(orgId)).thenReturn(Optional.of(new Organization("acme", "Acme")));
        when(users.findById(userId)).thenReturn(Optional.of(mock(UserAccount.class)));
        when(memberships.existsByOrgIdAndUserId(orgId, userId)).thenReturn(false);

        service.addMember(orgId, userId);

        verify(memberships).saveAndFlush(any(OrganizationMembership.class));
    }

    @Test
    void addMemberIsIdempotentForAnExistingMember() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(organizations.findById(orgId)).thenReturn(Optional.of(new Organization("acme", "Acme")));
        when(users.findById(userId)).thenReturn(Optional.of(mock(UserAccount.class)));
        when(memberships.existsByOrgIdAndUserId(orgId, userId)).thenReturn(true);

        service.addMember(orgId, userId);

        verify(memberships, never()).save(any());
    }

    @Test
    void addMemberToAMissingOrgThrowsNotFound() {
        UUID orgId = UUID.randomUUID();
        when(organizations.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(orgId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        verify(memberships, never()).save(any());
    }

    @Test
    void addMemberWithAMissingUserThrowsNotFoundAndDoesNotPersist() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(organizations.findById(orgId)).thenReturn(Optional.of(new Organization("acme", "Acme")));
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(orgId, userId)).isInstanceOf(NotFoundException.class);
        verify(memberships, never()).save(any());
    }

    @Test
    void removeMemberDeletesAndPublishesTheMembershipChangedEvent() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(memberships.existsByOrgIdAndUserId(orgId, userId)).thenReturn(true);

        service.removeMember(orgId, userId);

        verify(memberships).deleteByOrgIdAndUserId(orgId, userId);
        ArgumentCaptor<OrganizationMembershipChangedEvent> event =
                ArgumentCaptor.forClass(OrganizationMembershipChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().orgId()).isEqualTo(orgId);
        assertThat(event.getValue().userId()).isEqualTo(userId);
    }

    @Test
    void removeMemberOfANonMemberIsANoOpAndPublishesNothing() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(memberships.existsByOrgIdAndUserId(orgId, userId)).thenReturn(false);

        service.removeMember(orgId, userId);

        verify(memberships, never()).deleteByOrgIdAndUserId(any(), any());
        verify(events, never()).publishEvent(any());
    }
}
