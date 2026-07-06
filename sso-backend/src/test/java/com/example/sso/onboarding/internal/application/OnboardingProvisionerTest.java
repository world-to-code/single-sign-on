package com.example.sso.onboarding.internal.application;

import com.example.sso.organization.CompanyProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the async provisioner's state machine: a successful run marks PROVISIONING, provisions,
 * emails the invite, then marks INVITED (in order); a failure during provisioning marks FAILED and sends
 * no email (so no invite goes out for a tenant that didn't fully provision).
 */
@ExtendWith(MockitoExtension.class)
class OnboardingProvisionerTest {

    @Mock private OnboardingServiceImpl service;
    @Mock private OnboardingEmailSender email;

    @InjectMocks private OnboardingProvisioner provisioner;

    private final OnboardingSpec spec =
            new OnboardingSpec("acme", "Acme", CompanyProfile.empty(), "admin@acme.com", "Acme Admin");

    @Test
    void successProvisionsSendsTheInviteThenMarksInvited() {
        UUID id = UUID.randomUUID();
        when(service.provision(id, spec)).thenReturn(new OnboardingServiceImpl.ProvisionResult("admin@acme.com", "tok"));

        provisioner.onRequested(new OnboardingRequested(id, spec));

        InOrder order = inOrder(service, email);
        order.verify(service).markProvisioning(id);
        order.verify(service).provision(id, spec);
        order.verify(email).sendInvitation("admin@acme.com", "tok");
        order.verify(service).markInvited(id);
        verify(service, never()).markFailed(any(), any());
    }

    @Test
    void aFailureDuringProvisioningMarksFailedAndSendsNoEmail() {
        UUID id = UUID.randomUUID();
        when(service.provision(id, spec)).thenThrow(new RuntimeException("boom"));

        provisioner.onRequested(new OnboardingRequested(id, spec));

        verify(service).markFailed(eq(id), any());
        verify(email, never()).sendInvitation(any(), any());
        verify(service, never()).markInvited(any());
        verify(service, never()).markInviteFailed(any());
    }

    @Test
    void anEmailFailureAfterProvisioningMarksInviteFailedNotFailed() {
        // The tenant is already provisioned (committed); a bounced invite email must not undo it — it becomes
        // INVITE_FAILED (re-invitable), never FAILED (which would imply nothing was created).
        UUID id = UUID.randomUUID();
        when(service.provision(id, spec)).thenReturn(new OnboardingServiceImpl.ProvisionResult("admin@acme.com", "tok"));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(email).sendInvitation("admin@acme.com", "tok");

        provisioner.onRequested(new OnboardingRequested(id, spec));

        verify(service).markInviteFailed(id);
        verify(service, never()).markInvited(any());
        verify(service, never()).markFailed(any(), any());
    }
}
