package com.example.sso.user;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The org-scoped id queries that scope a deny's session-termination fan-out ({@code DenyAffectedUsers}). The
 * isolation guarantee is a real-DB property: {@code findIdsByOrgId} must return ONLY the given tenant's users so
 * a deny on a GLOBAL role can't terminate another tenant's holders, while {@code findAllIds} (the absolute
 * platform veto) must span every tenant. app_user has no RLS, so these run unscoped by design.
 */
class AppUserOrgScopeIT extends AbstractIntegrationTest {

    @Autowired
    AppUserRepository appUsers;
    @Autowired
    OrganizationService organizations;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void findIdsByOrgIdReturnsOnlyThatTenantsUsers() {
        UUID orgA = newOrg("scope-a");
        UUID orgB = newOrg("scope-b");
        UUID userA = newUserInOrg(orgA);
        UUID userB = newUserInOrg(orgB);
        UUID global = newGlobalUser();

        Set<UUID> inA = appUsers.findIdsByOrgId(orgA);

        assertThat(inA).contains(userA).doesNotContain(userB, global);
    }

    @Test
    void findAllIdsSpansEveryTenantAndTheGlobalTier() {
        UUID orgA = newOrg("all-a");
        UUID orgB = newOrg("all-b");
        UUID userA = newUserInOrg(orgA);
        UUID userB = newUserInOrg(orgB);
        UUID global = newGlobalUser();

        assertThat(appUsers.findAllIds()).contains(userA, userB, global);
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + shortId(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private UUID newUserInOrg(UUID orgId) {
        return insertUser(orgId);
    }

    private UUID newGlobalUser() {
        return insertUser(null);
    }

    private UUID insertUser(UUID orgId) {
        UUID id = UUID.randomUUID();
        String name = "scope-" + id.toString().substring(0, 8);
        ownerJdbc().update("insert into app_user (id, username, email, org_id) values (?, ?, ?, ?)",
                id, name, name + "@example.com", orgId);
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", id));
        return id;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
