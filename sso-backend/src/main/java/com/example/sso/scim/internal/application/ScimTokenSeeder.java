package com.example.sso.scim.internal.application;

import com.example.sso.scim.ScimTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds a stable dev SCIM bearer token so local provisioning calls can authenticate. Active
 * ONLY under the {@code dev}/{@code local} profiles — production never gets a well-known token;
 * there, tokens are issued via the admin API.
 */
@Component
@Profile({"dev", "local"})
public class ScimTokenSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScimTokenSeeder.class);

    private final ScimTokenService tokenService;
    private final String devToken;

    public ScimTokenSeeder(ScimTokenService tokenService,
                           @Value("${sso.scim.dev-token:dev-scim-token}") String devToken) {
        this.tokenService = tokenService;
        this.devToken = devToken;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (devToken == null || devToken.isBlank()) {
            return; // no dev token configured (e.g. production) — issue tokens via the admin API
        }
        tokenService.ensureToken(devToken, "Seeded dev token");
        log.info("Ensured dev SCIM bearer token (configure sso.scim.dev-token to change).");
    }
}
