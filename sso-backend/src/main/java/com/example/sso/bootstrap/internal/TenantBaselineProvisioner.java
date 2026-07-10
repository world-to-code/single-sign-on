package com.example.sso.bootstrap.internal;

import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.organization.OrganizationCreatedEvent;
import com.example.sso.session.SessionPolicyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Provisions a new tenant's baseline the moment its organization is created: its own editable default
 * session policy and login (auth) policy, so the tenant admin manages real, org-owned policies instead of
 * an empty page and the tenant no longer depends silently on the global fallback. Runs AFTER the creating
 * (signup/onboarding) transaction commits (so the org row exists) and on the {@code @Async} executor —
 * decoupled from the creating request, headed for extraction into a separate provisioning service. A
 * failure therefore never touches the caller; it is logged here WITH the orgId (the global async handler
 * deliberately logs no argument values), and because each {@code provisionDefault} is idempotent and
 * org-scoped (RLS), provisioning can simply be re-run and a re-delivered event is harmless.
 */
@Component
@RequiredArgsConstructor
public class TenantBaselineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantBaselineProvisioner.class);

    private final SessionPolicyService sessionPolicies;
    private final AuthPolicyAdminService authPolicies;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        try {
            sessionPolicies.provisionDefault(event.orgId());
            authPolicies.provisionDefault(event.orgId());
            log.info("Provisioned baseline session + auth policies for organization {}", event.orgId());
        } catch (Exception e) {
            // Never fatal to the (already committed) creation; observable with the affected org. Until the
            // tenant's own Default exists it inherits the strength-identical global fallback — re-run to heal.
            log.error("Tenant baseline provisioning failed for organization {} — re-run provisioning "
                    + "(idempotent)", event.orgId(), e);
        }
    }
}
