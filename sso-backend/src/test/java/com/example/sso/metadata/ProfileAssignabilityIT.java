package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * {@code requireAssignable} against a real database — the one check every route that binds a person to a
 * profile shares (creation, the CSV import and its template, the profile move).
 *
 * <p>It runs here rather than only in the callers' unit tests because both halves of it are things a mock
 * cannot hold: the org scoping is an {@code org_id} predicate plus RLS, and the refusal it exists for is
 * cross-tenant. While the create path trusted the client's id, a tenant admin could bind their own new account
 * to ANOTHER tenant's profile row — the declaration lookup is org-scoped, so the foreign profile declared
 * nothing and the required-column check passed over an empty set, leaving a live FK across the tenant boundary
 * that neither tenant can see.
 */
class ProfileAssignabilityIT extends AbstractIntegrationTest {

    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        for (UUID org : new UUID[] {orgA, orgB}) {
            if (org != null) {
                organizations.delete(org);
            }
        }
        orgA = null;
        orgB = null;
    }

    @Test
    void aTenantsOwnProfileGovernsItsUsers() {
        orgA = org("pa-own");

        UUID own = tenantProfileOf(orgA);

        assertThat(orgContext.callInOrg(orgA, () -> profiles.requireAssignable(own)).id()).isEqualTo(own);
    }

    /** Non-revealing on purpose: another tenant's id is indistinguishable from one that does not exist. */
    @Test
    void anotherTenantsProfileCannotBeBoundTo() {
        orgA = org("pa-a");
        orgB = org("pa-b");
        UUID foreign = tenantProfileOf(orgB);

        assertThatThrownBy(() -> orgContext.callInOrg(orgA, () -> profiles.requireAssignable(foreign)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anUnknownProfileIsRefusedTheSameWay() {
        orgA = org("pa-unknown");

        assertThatThrownBy(() -> orgContext.callInOrg(orgA, () -> profiles.requireAssignable(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * A SOURCE profile describes what a directory SENDS us. Binding a person to one promises a schema no
     * console form can fill, and {@code profile.connector_id} cascades while {@code app_user.profile_id} is ON
     * DELETE SET NULL — so deleting the connector would silently reset the bound user's schema.
     */
    @Test
    void aSourceProfileOfTheCallersOwnOrgIsRefused() {
        orgA = org("pa-source");
        UUID source = orgContext.callInOrg(orgA,
                () -> profiles.provisionForSource(orgA, ProfileKind.SCIM, "SCIM").id());

        assertThatThrownBy(() -> orgContext.callInOrg(orgA, () -> profiles.requireAssignable(source)))
                .isInstanceOf(BadRequestException.class);
    }

    /** The platform tier owns no profile, so nothing there is assignable — empty scope, not a free pass. */
    @Test
    void thePlatformTierCanBindToNothing() {
        orgA = org("pa-plat");
        UUID tenantOwned = tenantProfileOf(orgA);

        assertThatThrownBy(() -> orgContext.callAsPlatform(() -> profiles.requireAssignable(tenantOwned)))
                .isInstanceOf(NotFoundException.class);
    }

    private UUID tenantProfileOf(UUID org) {
        return orgContext.callInOrg(org, () -> profiles.tenantProfile().orElseThrow().id());
    }

    /** The baseline profile is provisioned AFTER_COMMIT, so reading it immediately races the provisioner. */
    private UUID org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = orgContext.callAsPlatform(() -> organizations.create(new NewOrganization(slug, slug)).id());
        await().until(() -> orgContext.callInOrg(id, () -> profiles.list()).stream()
                .anyMatch(profile -> profile.kind() == ProfileKind.TENANT));
        return id;
    }
}
