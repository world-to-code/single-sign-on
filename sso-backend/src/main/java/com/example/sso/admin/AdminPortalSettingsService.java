package com.example.sso.admin;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.shared.error.NotFoundException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Reads and updates the single-row {@link AdminPortalSettings}. On update it also propagates the
 * elevation-token TTL onto the {@code admin-console} {@link RegisteredClient} so freshly minted
 * elevation tokens honour the new lifetime without a restart.
 */
@Service
public class AdminPortalSettingsService {

    private final AdminPortalSettingsRepository repository;
    private final RegisteredClientRepository registeredClients;

    public AdminPortalSettingsService(AdminPortalSettingsRepository repository,
                                      RegisteredClientRepository registeredClients) {
        this.repository = repository;
        this.registeredClients = registeredClients;
    }

    @Transactional(readOnly = true)
    public AdminPortalSettings get() {
        return repository.findById(AdminPortalSettings.SINGLETON_ID)
                .orElseThrow(() -> new NotFoundException("admin portal settings not initialized"));
    }

    @Transactional
    public AdminPortalSettings update(AdminPortalSettingsRequest request) {
        AdminPortalSettings settings = get();
        settings.update(request.reauthIntervalMinutes(), request.elevationTokenTtlMinutes(),
                request.sessionIdleTimeoutMinutes(), request.sessionAbsoluteLifetimeMinutes());
        AdminPortalSettings saved = repository.save(settings);
        syncElevationTokenTtl(saved.getElevationTokenTtlMinutes());
        return saved;
    }

    private void syncElevationTokenTtl(int minutes) {
        RegisteredClient client = registeredClients.findByClientId(AdminPortalSeeder.CLIENT_ID);
        if (client == null) {
            return;
        }
        TokenSettings tokenSettings = TokenSettings.builder()
                .settings(s -> s.putAll(client.getTokenSettings().getSettings()))
                .accessTokenTimeToLive(Duration.ofMinutes(minutes))
                .build();
        registeredClients.save(RegisteredClient.from(client).tokenSettings(tokenSettings).build());
    }
}
