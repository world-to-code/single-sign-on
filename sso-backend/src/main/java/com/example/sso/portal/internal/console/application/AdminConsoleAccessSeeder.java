package com.example.sso.portal.internal.console.application;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.access.AdminConsoleAccess;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.AppAssignment;
import com.example.sso.portal.internal.catalog.domain.AppAssignmentRepository;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the assignments that grant console entry under the assignment-based entry model: {@code admin-console}
 * assigned to {@code ROLE_ADMIN} (super admins) and to {@code ROLE_ORG_ADMIN} (tenant admins, who manage their
 * own organization). Every holder inherits it via role resolution; it is revocable like any
 * other assignment. The assignment only lets a user REACH the console — what they can DO there stays gated by
 * their scoped permissions + drill-in authorization. Runs on {@link ApplicationReadyEvent} so it deterministically
 * follows both the role seeding and the {@link AdminPortalSeeder} client seeding, whatever their runner order.
 * Also the {@link AdminConsoleAccess} implementation: tenant baseline provisioning assigns the console to each
 * org's OWN {@code ROLE_ORG_ADMIN} through it (assignment resolution matches role IDS, so a per-org role needs
 * its own assignment row).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminConsoleAccessSeeder implements AdminConsoleAccess {

    private final RegisteredClientRepository clients;
    private final RoleService roles;
    private final AppAssignmentRepository assignments;
    private final OrgContext orgContext;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        assignToRole(roles.getOrCreateSystem(Roles.ADMIN).getId(), null);
        assignToRole(roles.getOrCreateSystem(Roles.ORG_ADMIN).getId(), null);
    }

    @Override
    @Transactional
    public void assignToRole(UUID roleId, UUID orgId) {
        RegisteredClient console = clients.findByClientId(AdminPortalSeeder.CLIENT_ID);
        if (console == null) {
            log.warn("Admin console client is not seeded; skipping the console assignment for role {}.", roleId);
            return;
        }
        if (orgId == null) {
            saveIfMissing(console, roleId, null);
        } else {
            // app_assignment is RLS-forced: both the idempotency check and the write must run in the org's
            // scope (an unbound check would miss the org row and insert a duplicate); flush inside the scope.
            orgContext.runInOrg(orgId, () -> saveIfMissing(console, roleId, orgId));
        }
    }

    private void saveIfMissing(RegisteredClient console, UUID roleId, UUID orgId) {
        boolean exists = assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                AppType.OIDC, console.getId(), AppAssignment.SubjectType.ROLE, roleId);
        if (exists) {
            return;
        }
        assignments.saveAndFlush(new AppAssignment(AppType.OIDC, console.getId(),
                AppAssignment.SubjectType.ROLE, roleId, null, orgId));
        log.info("Assigned the admin console to role {} (assignment-based console entry).", roleId);
    }
}
