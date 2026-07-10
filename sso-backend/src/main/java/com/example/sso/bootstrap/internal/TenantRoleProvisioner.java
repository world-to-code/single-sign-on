package com.example.sso.bootstrap.internal;

import com.example.sso.organization.OrganizationCreatedEvent;
import com.example.sso.organization.OrganizationService;
import com.example.sso.portal.AdminConsoleAccess;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Provisions a new tenant's OWN baseline system roles (ROLE_USER / ROLE_GROUP_ADMIN / ROLE_ORG_ADMIN)
 * and grants its ROLE_ORG_ADMIN admin-console entry. Unlike the policy baseline (async, {@link
 * TenantBaselineProvisioner}), this runs SYNCHRONOUSLY inside the creating transaction: the org's first
 * admin is created later in the SAME transaction and must be assigned the ORG's roles, not the global
 * fallbacks — and a failure here correctly rolls the whole creation back (roles are the org's identity
 * baseline). ROLE_ADMIN stays platform-only.
 *
 * <p>On startup it also backfills organizations created before per-org roles existed: provisions their
 * baseline and migrates their members off the GLOBAL baseline roles onto the org's own (idempotent —
 * migration only touches users still holding a global baseline role, and their sessions are terminated
 * by the role-membership change so stale authorities never outlive it).
 */
@Component
@RequiredArgsConstructor
public class TenantRoleProvisioner {

    private static final Logger log = LoggerFactory.getLogger(TenantRoleProvisioner.class);

    private static final List<String> BASELINE_ROLE_NAMES =
            List.of(Roles.USER, Roles.GROUP_ADMIN, Roles.ORG_ADMIN);

    private final RbacService rbac;
    private final RoleService roles;
    private final AdminConsoleAccess consoleAccess;
    private final OrganizationService organizations;
    private final OrgContext orgContext;

    @EventListener
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        provision(event.orgId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingOrganizations() {
        orgContext.callAsPlatform(organizations::listAll).forEach(org -> {
            Map<String, UUID> provisioned = provision(org.id());
            migrateMembersToOrgRoles(org.id(), provisioned);
        });
    }

    private Map<String, UUID> provision(UUID orgId) {
        Map<String, UUID> provisioned = rbac.provisionBaselineRoles(orgId);
        consoleAccess.assignToRole(provisioned.get(Roles.ORG_ADMIN), orgId);
        return provisioned;
    }

    /**
     * Moves the org's members (users whose identity belongs to the org) off a GLOBAL baseline role onto
     * the org's own copy. Grant-then-revoke, so a holder never has a window without the role. Runs in the
     * org's scope: the role table is RLS-forced, so the org role is invisible to the (unbound) startup
     * thread otherwise; global rows stay visible inside an org scope.
     */
    private void migrateMembersToOrgRoles(UUID orgId, Map<String, UUID> orgRoles) {
        orgContext.runInOrg(orgId, () -> BASELINE_ROLE_NAMES.forEach(name ->
                roles.findByName(name).ifPresent(global ->
                        roles.members(global.getId()).stream()
                                .filter(user -> orgId.equals(user.getOrgId()))
                                .forEach(user -> {
                                    roles.addMember(orgRoles.get(name), user.getId());
                                    roles.removeMember(global.getId(), user.getId());
                                    log.info("Migrated {} from the global {} to organization {}'s own role",
                                            user.getUsername(), name, orgId);
                                }))));
    }
}
