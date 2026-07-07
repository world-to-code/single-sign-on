package com.example.sso.portal.internal.application;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.AppType;
import com.example.sso.portal.internal.domain.AppAssignment;
import com.example.sso.portal.internal.domain.AppAssignmentRepository;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminConsoleAccessSeeder {

    private final RegisteredClientRepository clients;
    private final RoleService roles;
    private final AppAssignmentRepository assignments;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        RegisteredClient console = clients.findByClientId(AdminPortalSeeder.CLIENT_ID);
        if (console == null) {
            log.warn("Admin console client is not seeded; skipping the console role assignments.");
            return;
        }
        assignConsoleToRole(console, Roles.ADMIN);
        assignConsoleToRole(console, Roles.ORG_ADMIN);
    }

    private void assignConsoleToRole(RegisteredClient console, String roleName) {
        UUID roleId = roles.getOrCreateSystem(roleName).getId(); // idempotent, order-proof
        boolean exists = assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                AppType.OIDC, console.getId(), AppAssignment.SubjectType.ROLE, roleId);
        if (!exists) {
            assignments.save(new AppAssignment(AppType.OIDC, console.getId(),
                    AppAssignment.SubjectType.ROLE, roleId, null));
            log.info("Assigned the admin console to {} (assignment-based console entry).", roleName);
        }
    }
}
