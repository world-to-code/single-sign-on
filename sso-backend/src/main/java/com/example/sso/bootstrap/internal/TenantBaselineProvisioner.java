package com.example.sso.bootstrap.internal;

import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.organization.OrganizationCreatedEvent;
import com.example.sso.session.SessionPolicyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Provisions a new tenant's baseline the moment its organization is created: its own editable default
 * session policy and login (auth) policy, so the tenant admin manages real, org-owned policies instead of
 * an empty page and the tenant no longer depends silently on the global fallback. Runs AFTER the creating
 * (signup/onboarding) transaction commits, so the org row exists; each {@code provisionDefault} is
 * idempotent and org-scoped (RLS), so a re-delivered event is harmless.
 */
@Component
@RequiredArgsConstructor
public class TenantBaselineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantBaselineProvisioner.class);

    private final SessionPolicyService sessionPolicies;
    private final AuthPolicyAdminService authPolicies;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        sessionPolicies.provisionDefault(event.orgId());
        authPolicies.provisionDefault(event.orgId());
        log.info("Provisioned baseline session + auth policies for organization {}", event.orgId());
    }
}
