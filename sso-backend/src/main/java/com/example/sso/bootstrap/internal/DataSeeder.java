package com.example.sso.bootstrap.internal;

import com.example.sso.authpolicy.policy.AuthPolicyAdminService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.role.Roles;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.rbac.RbacService;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.account.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Ensures baseline roles exist and seeds a default admin account on first start.
 * Idempotent: safe to run on every boot.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** The seeded tenant every pre-existing user is backfilled into (see V41 migration). */
    private static final String DEFAULT_ORG_SLUG = "default";

    private final UserService userService;
    private final RoleService roleService;
    private final RbacService rbacService;
    private final AuthPolicyAdminService authPolicyService;
    private final OrganizationService organizationService;
    private final String adminUsername;
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UserService userService,
                      RoleService roleService,
                      RbacService rbacService,
                      AuthPolicyAdminService authPolicyService,
                      OrganizationService organizationService,
                      @Value("${sso.admin.username:admin}") String adminUsername,
                      @Value("${sso.admin.email:admin@example.com}") String adminEmail,
                      @Value("${sso.admin.password:admin123!}") String adminPassword) {
        this.userService = userService;
        this.roleService = roleService;
        this.rbacService = rbacService;
        this.authPolicyService = authPolicyService;
        this.organizationService = organizationService;
        this.adminUsername = adminUsername;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        roleService.getOrCreateSystem(Roles.USER);
        roleService.getOrCreateSystem(Roles.ADMIN);
        roleService.getOrCreateSystem(Roles.GROUP_ADMIN);
        roleService.getOrCreateSystem(Roles.ORG_ADMIN);
        rbacService.grantAllPermissionsToAdmin();
        rbacService.grantGroupAdminPermissions();
        rbacService.grantOrgAdminPermissions();
        rbacService.seedGlobalRoleHierarchy();
        authPolicyService.seedDefault();

        if (!userService.existsByUsername(adminUsername)) {
            userService.createUser(new NewUser(adminUsername, adminEmail, "Administrator",
                    adminPassword, Set.of(Roles.ADMIN, Roles.USER)));
            log.warn("Seeded default admin user '{}'. CHANGE THIS PASSWORD before any real use.",
                    adminUsername);
        }

        userService.findByUsername(adminUsername).ifPresent(admin -> {
            // The bootstrap admin is a system account — pre-verify its email so it is never locked out.
            if (!admin.isEmailVerified()) {
                userService.markEmailVerified(admin.getId());
            }
            // On a fresh DB the V41 backfill ran before the admin existed, so add it to the default org here.
            organizationService.findBySlug(DEFAULT_ORG_SLUG)
                    .ifPresent(org -> organizationService.addMember(org.getId(), admin.getId()));
        });
    }
}
