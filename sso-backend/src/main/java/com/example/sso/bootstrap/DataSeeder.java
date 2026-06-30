package com.example.sso.bootstrap;

import com.example.sso.authpolicy.AuthPolicyAdminService;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.RbacService;
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
    private final RbacService rbacService;
    private final AuthPolicyAdminService authPolicyService;
    private final AppUserRepository users;
    private final String adminUsername;
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UserService userService,
                      RbacService rbacService,
                      AuthPolicyAdminService authPolicyService,
                      AppUserRepository users,
                      @Value("${sso.admin.username:admin}") String adminUsername,
                      @Value("${sso.admin.email:admin@example.com}") String adminEmail,
                      @Value("${sso.admin.password:admin123!}") String adminPassword) {
        this.userService = userService;
        this.rbacService = rbacService;
        this.authPolicyService = authPolicyService;
        this.users = users;
        this.adminUsername = adminUsername;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        userService.getOrCreateRole("ROLE_USER");
        userService.getOrCreateRole("ROLE_ADMIN");
        rbacService.grantAllPermissionsToAdmin();
        authPolicyService.seedDefault();

        if (!users.existsByUsername(adminUsername)) {
            userService.createUser(adminUsername, adminEmail, "Administrator",
                    adminPassword, Set.of("ROLE_ADMIN", "ROLE_USER"));
            log.warn("Seeded default admin user '{}'. CHANGE THIS PASSWORD before any real use.",
                    adminUsername);
        }
        // The bootstrap admin is a system account — pre-verify its email so it is never locked out.
        users.findByUsername(adminUsername)
                .filter(admin -> !admin.isEmailVerified())
                .ifPresent(userService::markEmailVerified);
    }
}
