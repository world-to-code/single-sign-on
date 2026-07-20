package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A tenant gets one profile the moment it exists.
 *
 * <p>The profile is the unit every later piece hangs off — attribute definitions, the mappings that carry
 * directory values in, and the shape of the user-creation form. A tenant without one would have nowhere to
 * declare what a user of theirs even looks like, so it is seeded with the org rather than left to an admin
 * to discover.
 */
class ProfileProvisioningIT extends AbstractIntegrationTest {

    @Autowired
    ProfileService profiles;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private UUID orgA;

    @AfterEach
    void tearDown() {
        if (orgA != null) {
            organizations.delete(orgA);
        }
    }

    private UUID org(String slug) {
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private List<Profile> profilesOf(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> profiles.list());
    }

    @Test
    void creatingAnOrganizationSeedsAProfileNamedAfterIt() {
        String slug = "profile-it-" + UUID.randomUUID().toString().substring(0, 8);
        orgA = org(slug);

        await().until(() -> !profilesOf(orgA).isEmpty());

        List<Profile> seeded = profilesOf(orgA);
        assertThat(seeded).hasSize(1);
        Profile tenant = seeded.getFirst();
        assertThat(tenant.name()).isEqualTo(slug);
        assertThat(tenant.kind()).isEqualTo(ProfileKind.TENANT);
        assertThat(tenant.system()).isTrue();                 // the tenant's own profile is not deletable
        assertThat(tenant.defaultForCreation()).isTrue();     // and it is what user creation uses until told otherwise
    }

    /** The event can be re-delivered and provisioning is meant to be re-runnable to heal a failure. */
    @Test
    void provisioningTwiceLeavesOneProfile() {
        String slug = "profile-it-" + UUID.randomUUID().toString().substring(0, 8);
        orgA = org(slug);
        await().until(() -> !profilesOf(orgA).isEmpty());

        profiles.provisionDefault(orgA);

        assertThat(profilesOf(orgA)).hasSize(1);
    }

    /** A tenant sees only its own profiles — the list is the surface every later screen reads from. */
    @Test
    void aTenantSeesOnlyItsOwnProfiles() {
        String slugA = "profile-it-a-" + UUID.randomUUID().toString().substring(0, 8);
        orgA = org(slugA);
        UUID orgB = org("profile-it-b-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            await().until(() -> !profilesOf(orgA).isEmpty() && !profilesOf(orgB).isEmpty());

            assertThat(profilesOf(orgA)).extracting(Profile::name).containsExactly(slugA);
            assertThat(profilesOf(orgB)).extracting(Profile::name).doesNotContain(slugA);
        } finally {
            organizations.delete(orgB);
        }
    }
}
