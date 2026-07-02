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
 * Seeds the assignment that keeps super admins in the console under the assignment-based entry model:
 * {@code admin-console} assigned to {@code ROLE_ADMIN} (every direct admin inherits it; revocable like
 * any other assignment). Runs on {@link ApplicationReadyEvent} so it deterministically follows both the
 * role seeding and the {@link AdminPortalSeeder} client seeding, whatever their runner order.
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
            log.warn("Admin console client is not seeded; skipping the ROLE_ADMIN console assignment.");
            return;
        }

        UUID adminRoleId = roles.getOrCreateSystem(Roles.ADMIN).getId(); // idempotent, order-proof
        boolean exists = assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                AppType.OIDC, console.getId(), AppAssignment.SubjectType.ROLE, adminRoleId);
        if (!exists) {
            assignments.save(new AppAssignment(AppType.OIDC, console.getId(),
                    AppAssignment.SubjectType.ROLE, adminRoleId, null));
            log.info("Assigned the admin console to ROLE_ADMIN (assignment-based console entry).");
        }
    }
}
