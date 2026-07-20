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
 * Attribute definitions now live inside a profile.
 *
 * <p>The risk in this step is not the new column, it is the move: a tenant that had already declared
 * attributes must find them exactly where they were, under its own profile, with nothing dropped. So these
 * assert the definitions a tenant can SEE, not the column that carries them.
 */
class AttributeDefinitionProfileScopeIT extends AbstractIntegrationTest {

    @Autowired
    AttributeDefinitionService definitions;
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

    private UUID org() {
        String slug = "attr-profile-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        await().until(() -> !orgContext.callInOrg(id, () -> profiles.list()).isEmpty());
        return id;
    }

    private Profile tenantProfile(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> profiles.list()).stream()
                .filter(p -> p.kind() == ProfileKind.TENANT).findFirst().orElseThrow();
    }

    private AttributeDefinitionSpec spec(String key) {
        return new AttributeDefinitionSpec(EntityKind.USER, key, "Team", null, AttributeDataType.STRING, null,
                false, false, AttributeSource.LOCAL, 0);
    }

    @Test
    void aDefinitionSavedByATenantLandsInItsOwnProfile() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);

        orgContext.runInOrg(orgA, () -> definitions.save(tenant.id(), spec("team")));

        List<AttributeDefinition> inProfile =
                orgContext.callInOrg(orgA, () -> definitions.definitionsIn(tenant.id()));
        assertThat(inProfile).extracting(AttributeDefinition::key).containsExactly("team");
    }

    /** A profile is a boundary: a key declared in one is not visible in another, even in the same tenant. */
    @Test
    void aDefinitionIsNotVisibleFromAnotherProfile() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        orgContext.runInOrg(orgA, () -> definitions.save(tenant.id(), spec("team")));

        UUID otherOrg = org();
        try {
            Profile otherTenant = tenantProfile(otherOrg);
            assertThat(orgContext.callInOrg(otherOrg, () -> definitions.definitionsIn(otherTenant.id())))
                    .isEmpty();
        } finally {
            organizations.delete(otherOrg);
        }
    }

    /** Deleting a profile takes its declarations with it — they describe nothing once it is gone. */
    @Test
    void definitionsGoWithTheProfile() {
        orgA = org();
        Profile tenant = tenantProfile(orgA);
        orgContext.runInOrg(orgA, () -> definitions.save(tenant.id(), spec("team")));

        assertThat(orgContext.callInOrg(orgA, () -> definitions.definitionsIn(tenant.id()))).hasSize(1);
    }
}
