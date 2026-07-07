package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.Onboarding;
import com.example.sso.onboarding.internal.domain.OnboardingRepository;
import com.example.sso.onboarding.internal.domain.OnboardingStatus;
import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.organization.OrganizationView;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the onboarding orchestration: {@code start} records the job and fires the event, and
 * {@code provision} creates the org + an INACTIVE admin bearing ROLE_ORG_ADMIN + membership + a set-password
 * invitation, linking the results back onto the job.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceImplTest {

    @Mock private OnboardingRepository onboardings;
    @Mock private OrganizationService organizations;
    @Mock private UserService users;
    @Mock private OnboardingInvitationService invitations;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks private OnboardingServiceImpl service;

    private OnboardingSpec spec() {
        return new OnboardingSpec("acme", "Acme", CompanyProfile.empty(), "admin@acme.com", "Acme Admin");
    }

    @Test
    void startRecordsPendingAndPublishesTheProvisioningEvent() {
        Onboarding saved = mock(Onboarding.class);
        when(saved.getId()).thenReturn(UUID.randomUUID());
        when(saved.getStatus()).thenReturn(OnboardingStatus.PENDING);
        when(saved.getSlug()).thenReturn("acme");
        when(onboardings.save(any(Onboarding.class))).thenReturn(saved);

        OnboardingView view = service.start(spec());

        assertThat(view.status()).isEqualTo(OnboardingStatus.PENDING);
        verify(events).publishEvent(any(OnboardingRequested.class));
    }

    @Test
    void provisionCreatesTheOrgInactiveAdminRoleMembershipAndInvitation() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Onboarding onboarding = mock(Onboarding.class);
        when(onboardings.findById(id)).thenReturn(Optional.of(onboarding));
        when(organizations.create(any())).thenReturn(new OrganizationView(orgId, "acme", "Acme",
                OrganizationStatus.ACTIVE, Instant.now(), CompanyProfile.empty(), false));
        UserAccount admin = mock(UserAccount.class);
        when(admin.getId()).thenReturn(adminId);
        when(users.createUser(any(), any())).thenReturn(admin);
        when(invitations.issue(eq(adminId), any())).thenReturn("raw-token");

        OnboardingServiceImpl.ProvisionResult result = service.provision(id, spec());

        // the admin is a ROLE_ORG_ADMIN, created WITHOUT a password, and immediately disabled (inactive)
        verify(users).createUser(argThat(u -> u.roleNames().contains(Roles.ORG_ADMIN)
                && u.roleNames().contains(Roles.USER) && u.rawPassword() == null), any());
        verify(users).disable(adminId);
        verify(organizations).addMember(orgId, adminId);
        verify(invitations).issue(eq(adminId), any());
        verify(onboarding).linkProvisioned(orgId, adminId);
        assertThat(result.adminEmail()).isEqualTo("admin@acme.com");
        assertThat(result.rawToken()).isEqualTo("raw-token");
    }
}
