package com.example.sso.organization;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the V41 migration + DataSeeder foundation: the {@code default} organization is seeded and the
 * bootstrap admin is backfilled into it as a member.
 */
class OrganizationSeedIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;
    @Autowired
    UserService users;

    @Test
    void defaultOrganizationIsSeededAndTheAdminIsAMember() {
        OrganizationRef defaultOrg = organizations.findBySlug("default").orElseThrow();
        assertThat(defaultOrg.getName()).isEqualTo("Default");
        assertThat(defaultOrg.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);

        UUID adminId = users.findByLogin("admin").map(UserAccount::getId).orElseThrow();
        assertThat(organizations.isMember(defaultOrg.getId(), adminId)).isTrue();
        assertThat(organizations.orgIdsForUser(adminId)).contains(defaultOrg.getId());
    }
}
