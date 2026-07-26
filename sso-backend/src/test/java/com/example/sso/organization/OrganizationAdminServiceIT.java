package com.example.sso.organization;

import com.example.sso.admin.internal.organization.application.OrgDenyView;
import com.example.sso.admin.internal.organization.application.OrganizationAdminService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.deny.DenyRow;
import com.example.sso.user.rbac.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end (service → repo → Postgres) coverage of the organization admin CRUD: the seeded default org
 * is listed, a create/get/delete round-trips, and slug uniqueness is enforced against the real DB.
 */
class OrganizationAdminServiceIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationAdminService organizations;
    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminListsAllOrganizationsIncludingTheSeededDefault() {
        // The list is org-scope-filtered; the seeded super admin (unscoped) sees every org.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));

        assertThat(organizations.list(0, 100).items())
                .anyMatch(o -> o.slug().equals("default"));
    }

    @Test
    void createGetAndDeleteRoundTrip() {
        String slug = "acme-" + suffix();
        OrganizationView created = organizations.create(new NewOrganization(slug, "Acme"));
        cleanups.add(() -> organizations.delete(created.id()));

        assertThat(created.slug()).isEqualTo(slug);
        assertThat(created.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(organizations.get(created.id()).name()).isEqualTo("Acme");

        OrganizationView renamed = organizations.update(created.id(), "Acme Corp", OrganizationStatus.SUSPENDED);
        assertThat(renamed.name()).isEqualTo("Acme Corp");
        assertThat(renamed.status()).isEqualTo(OrganizationStatus.SUSPENDED);
    }

    @Test
    void createRejectsADuplicateSlug() {
        String slug = "dup-" + suffix();
        OrganizationView created = organizations.create(new NewOrganization(slug, "Dup"));
        cleanups.add(() -> organizations.delete(created.id()));

        assertThatThrownBy(() -> organizations.create(new NewOrganization(slug, "Dup Two")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deniesOffersTheTenantGrantableCatalogAndTheOrgsOwnDenies() {
        String slug = "deny-" + suffix();
        OrganizationView created = organizations.create(new NewOrganization(slug, "Deny Co"));
        cleanups.add(() -> organizations.delete(created.id()));
        UUID orgId = created.id();
        ownerJdbc().update("insert into org_permission_deny (id, org_id, pattern) values (gen_random_uuid(), ?, ?)",
                orgId, Permissions.USER_READ);

        OrgDenyView view = orgContext.callAsPlatform(() -> organizations.denies(orgId)); // super console context

        // Candidates are EXACTLY the tenant-grantable catalog — a widening to the full (platform-including)
        // catalog, which would present un-withholdable platform perms, is caught here.
        assertThat(view.candidates()).containsExactlyInAnyOrderElementsOf(Permissions.tenantGrantable());
        assertThat(view.denies()).extracting(DenyRow::pattern).containsExactly(Permissions.USER_READ);
    }

    @Test
    void deniesForAnUnknownOrgIsNotFound() {
        assertThatThrownBy(() -> organizations.denies(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
