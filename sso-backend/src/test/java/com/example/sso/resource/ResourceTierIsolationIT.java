package com.example.sso.resource;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.resource.internal.application.ResourceAdminService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Org-tier isolation for the resource admin service. A tenant tier-admin ({@code ROLE_ORG_ADMIN} bound to
 * its org) may manage its OWN org's resource tree, but resource RLS lets ANY context READ a GLOBAL
 * ({@code org_id NULL}) row — so the service must ALSO tier-check every loaded resource in code
 * ({@code OrgTierGuard.requireInTier}). Without that backstop a tenant admin holding the now-tenant-grantable
 * {@code resource:*} could read / rename / DELETE global resources it does not own — and the V56 backfill
 * makes every legacy resource global, so the whole legacy tree would be exposed. A tier mismatch must be a
 * non-revealing 404, never a success (nor a raw RLS 500).
 *
 * <p>Foreign-org rows (owned by ANOTHER tenant) are already hidden by RLS {@code USING}; this test targets
 * the global rows RLS deliberately keeps visible.
 */
class ResourceTierIsolationIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService service;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private UUID typeId;
    private UUID globalResource;
    private UUID orgResource;
    private UUID orgId;

    @BeforeEach
    void seed() {
        ResourceType type = types.save(new ResourceType("TIER-ANY-" + suffix()));
        typeId = type.getId();
        // A GLOBAL resource (org null) — as a legacy/platform row would be after the V56 backfill.
        globalResource = resources.save(new Resource("Tier-Global-" + suffix(), type, null)).getId();
        // A resource owned by tenant org X, created in X's context (RLS WITH CHECK stamps + admits it).
        orgId = organizations.create(new NewOrganization("tier-" + suffix(), "Tier")).id();
        orgResource = orgContext.callInOrg(orgId,
                () -> resources.save(new Resource("Tier-Org-" + suffix(), type, orgId)).getId());
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        orgContext.runAsPlatform(() -> {
            resources.deleteById(orgResource);
            resources.deleteById(globalResource);
            types.deleteById(typeId);
        });
        ownerJdbc().update("delete from organization where id = ?", orgId);
    }

    @Test
    void aTenantAdminCannotReadAGlobalResource() {
        actAsTenantAdmin();
        orgContext.runInOrg(orgId, () -> {
            assertThatThrownBy(() -> service.get(globalResource)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.detail(globalResource)).isInstanceOf(NotFoundException.class);
        });
    }

    @Test
    void aTenantAdminCannotRenameOrDeleteAGlobalResource() {
        actAsTenantAdmin();
        orgContext.runInOrg(orgId, () -> {
            assertThatThrownBy(() -> service.rename(globalResource, "pwned")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.delete(globalResource)).isInstanceOf(NotFoundException.class);
        });
        // The global resource must still exist — the tenant delete was refused, not silently applied.
        assertThat(orgContext.callAsPlatform(() -> resources.findById(globalResource))).isPresent();
    }

    @Test
    void aTenantAdminCannotDetachAGlobalResourcesEdgesOrMembers() {
        actAsTenantAdmin();
        orgContext.runInOrg(orgId, () -> {
            assertThatThrownBy(() -> service.detachChild(globalResource, UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.detachMember(globalResource, MemberType.GROUP,
                    UUID.randomUUID().toString())).isInstanceOf(NotFoundException.class);
        });
    }

    @Test
    void aTenantAdminManagesItsOwnOrgResource() {
        // Positive control: the tier backstop must NOT over-tighten — a tenant admin still manages its own row.
        actAsTenantAdmin();
        orgContext.runInOrg(orgId, () -> {
            assertThatCode(() -> service.get(orgResource)).doesNotThrowAnyException();
            assertThatCode(() -> service.rename(orgResource, "Tier-Org-renamed")).doesNotThrowAnyException();
        });
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void actAsTenantAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tier-admin", null, List.of(
                        new SimpleGrantedAuthority(Roles.ORG_ADMIN),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_READ),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_UPDATE),
                        new SimpleGrantedAuthority(Permissions.RESOURCE_DELETE))));
    }
}
