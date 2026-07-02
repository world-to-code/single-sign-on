package com.example.sso.resource.internal.domain;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for the {@link ResourceGrant} tier factories. */
class ResourceGrantTest {

    @Test
    void adminFactoryBuildsAnAdminTierGrantWithNoRole() {
        UUID user = UUID.randomUUID();

        ResourceGrant grant = ResourceGrant.admin(user);

        assertThat(grant.userId()).isEqualTo(user);
        assertThat(grant.tier()).isEqualTo(ResourceRoleTier.ADMIN);
        assertThat(grant.roleId()).isNull();
    }

    @Test
    void viewerFactoryBuildsAViewerTierGrantWithNoRole() {
        UUID user = UUID.randomUUID();

        ResourceGrant grant = ResourceGrant.viewer(user);

        assertThat(grant.userId()).isEqualTo(user);
        assertThat(grant.tier()).isEqualTo(ResourceRoleTier.VIEWER);
        assertThat(grant.roleId()).isNull();
    }
}
