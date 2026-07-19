package com.example.sso.auth.internal.login.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Just-in-time provisioning must be ONE transaction. If the account commits but membership does not, the
 * orphan exists as a NON-member, and every future federated login takes the "account exists, not a member"
 * branch and is refused forever — the tenant cannot re-provision it and the user can never sign in.
 *
 * <p>This asserts the BEHAVIOUR (nothing survives a failed provision) rather than the annotation, because
 * {@code @Transactional} silently does nothing on a non-public method under Spring's default proxy setup
 * ({@code publicMethodsOnly = true}) — exactly the trap this test exists to catch. It must run OUTSIDE any
 * ambient test transaction, or the rollback would be the test's rather than the provisioner's.
 */
class FederatedUserProvisionerAtomicityIT extends AbstractIntegrationTest {

    @Autowired
    FederatedUserProvisioner provisioner;

    @Autowired
    UserService users;

    @Autowired
    OrgContext orgContext;

    @MockitoSpyBean
    OrganizationService organizations;

    @Test
    void aFailedMembershipRollsBackTheAccountInsteadOfOrphaningIt() {
        UUID orgId = organizations.create(new NewOrganization("jit-atomic-" + suffix(), "JIT atomic")).id();
        String email = "orphan-" + suffix() + "@example.test";
        FederatedIdentity identity = new FederatedIdentity("okta", "https://okta.test", "sub-" + suffix(),
                email, true, "Orphan", true, false);

        doThrow(new IllegalStateException("membership write failed"))
                .when(organizations).addMember(any(), any());

        assertThatThrownBy(() -> orgContext.callInOrg(orgId, () -> provisioner.provision(identity, orgId)))
                .isInstanceOf(IllegalStateException.class);

        // The account must NOT be readable afterwards: a committed orphan wedges this identity permanently.
        assertThat(orgContext.callInOrg(orgId, () -> users.findByLoginInOrg(email, orgId))).isEmpty();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
