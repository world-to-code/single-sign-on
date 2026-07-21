package com.example.sso.bootstrap.internal;

import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.organization.OrganizationCreatedEvent;
import com.example.sso.session.policy.SessionPolicyService;
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
    private final ProfileService profiles;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        try {
            sessionPolicies.provisionDefault(event.orgId());
            authPolicies.provisionDefault(event.orgId());
            // The tenant's own profile: the unit attribute definitions and directory mappings hang off.
            profiles.provisionDefault(event.orgId());
            // SCIM pushes to us whether or not anyone configured a connector, so the schema describing what it
            // sends exists from the start — otherwise the first push would have nowhere to map from.
            profiles.provisionForSource(event.orgId(), ProfileKind.SCIM, "SCIM");
            // No CSV profile: an upload does not describe a source we read FROM. The administrator picks one
            // of their own profiles, downloads its template, and the columns ARE that profile's attributes —
            // so there is nothing to map between and no second schema to keep in step.
            log.info("Provisioned baseline session + auth policies and profile for organization {}", event.orgId());
        } catch (Exception e) {
            // Never fatal to the (already committed) creation; observable with the affected org. Until the
            // tenant's own Default exists it inherits the strength-identical global fallback — re-run to heal.
            log.error("Tenant baseline provisioning failed for organization {} — re-run provisioning "
                    + "(idempotent)", event.orgId(), e);
        }
    }
}
