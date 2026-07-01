package com.example.sso.bootstrap.internal;

import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserService;
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

    private final UserService userService;
    private final RoleService roleService;
    private final RbacService rbacService;
    private final AuthPolicyAdminService authPolicyService;
    private final String adminUsername;
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UserService userService,
                      RoleService roleService,
                      RbacService rbacService,
                      AuthPolicyAdminService authPolicyService,
                      @Value("${sso.admin.username:admin}") String adminUsername,
                      @Value("${sso.admin.email:admin@example.com}") String adminEmail,
                      @Value("${sso.admin.password:admin123!}") String adminPassword) {
        this.userService = userService;
        this.roleService = roleService;
        this.rbacService = rbacService;
        this.authPolicyService = authPolicyService;
        this.adminUsername = adminUsername;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        roleService.getOrCreateSystem("ROLE_USER");
        roleService.getOrCreateSystem("ROLE_ADMIN");
        rbacService.grantAllPermissionsToAdmin();
        authPolicyService.seedDefault();

        if (!userService.existsByUsername(adminUsername)) {
            userService.createUser(adminUsername, adminEmail, "Administrator",
                    adminPassword, Set.of("ROLE_ADMIN", "ROLE_USER"));
            log.warn("Seeded default admin user '{}'. CHANGE THIS PASSWORD before any real use.",
                    adminUsername);
        }
        // The bootstrap admin is a system account — pre-verify its email so it is never locked out.
        userService.findByUsername(adminUsername)
                .filter(admin -> !admin.isEmailVerified())
                .ifPresent(admin -> userService.markEmailVerified(admin.getId()));
    }
}
