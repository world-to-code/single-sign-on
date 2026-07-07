package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.Onboarding;
import com.example.sso.onboarding.internal.domain.OnboardingRepository;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.NewUser;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates tenant onboarding. {@link #start} records the job PENDING and publishes an event; the async
 * {@link OnboardingProvisioner} then drives the transactional steps here (create the org + admin + role +
 * membership + invitation, then mark INVITED, or FAILED). Splitting the DB steps into separate transactions
 * (called cross-bean from the provisioner) gives honest, pollable status transitions.
 */
@Service
@RequiredArgsConstructor
public class OnboardingServiceImpl {

    private final OnboardingRepository onboardings;
    private final OrganizationService organizations;
    private final UserService users;
    private final OnboardingInvitationService invitations;
    private final ApplicationEventPublisher events;

    @Value("${sso.onboarding.invitation-ttl:72h}")
    private Duration invitationTtl;

    /** Platform-admin entry: records the job PENDING and fires the async provisioning. Returns immediately. */
    @Transactional
    public OnboardingView start(OnboardingSpec spec) {
        Onboarding onboarding = onboardings.save(new Onboarding(spec.slug()));
        events.publishEvent(new OnboardingRequested(onboarding.getId(), spec));
        return OnboardingView.of(onboarding);
    }

    @Transactional(readOnly = true)
    public OnboardingView status(UUID id) {
        return OnboardingView.of(require(id));
    }

    @Transactional
    public void markProvisioning(UUID id) {
        require(id).markProvisioning();
    }

    /** Creates the org + admin (inactive) + ROLE_ORG_ADMIN + membership + a set-password invitation. */
    @Transactional
    public ProvisionResult provision(UUID id, OnboardingSpec spec) {
        Onboarding onboarding = require(id);
        OrganizationView org = organizations.create(new NewOrganization(spec.slug(), spec.name(), spec.profile()));
        // The admin belongs to the new organization, created INACTIVE (disabled, no password) — the invitation
        // activates it.
        UserAccount admin = users.createUser(new NewUser(spec.adminEmail(), spec.adminEmail(), spec.adminName(),
                null, Set.of(Roles.USER, Roles.ORG_ADMIN)), org.id());
        users.disable(admin.getId());
        organizations.addMember(org.id(), admin.getId());
        String token = invitations.issue(admin.getId(), invitationTtl);
        onboarding.linkProvisioned(org.id(), admin.getId());
        // Use the org's CANONICAL slug (normalized on create), not the raw request, so the emailed
        // workspace URL matches the real subdomain.
        return new ProvisionResult(spec.adminEmail(), token, org.slug());
    }

    @Transactional
    public void markInvited(UUID id) {
        require(id).markInvited();
    }

    @Transactional
    public void markInviteFailed(UUID id) {
        require(id).markInviteFailed();
    }

    @Transactional
    public void markFailed(UUID id, String reason) {
        require(id).markFailed(reason);
    }

    private Onboarding require(UUID id) {
        return onboardings.findById(id).orElseThrow(() -> new NotFoundException("onboarding not found"));
    }

    /** The result the async worker needs after provisioning to send the invitation email. */
    record ProvisionResult(String adminEmail, String rawToken, String slug) {
    }
}
