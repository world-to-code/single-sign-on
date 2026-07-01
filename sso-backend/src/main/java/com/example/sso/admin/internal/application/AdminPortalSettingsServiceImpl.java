package com.example.sso.admin.internal.application;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.admin.internal.domain.AdminPortalSettings;
import com.example.sso.admin.internal.domain.AdminPortalSettingsRepository;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Default {@link AdminPortalSettingsService}. Maps the single-row {@code AdminPortalSettings} entity
 * to the public {@link AdminPortalSettingsData} projection. On update it also propagates the
 * elevation-token TTL onto the {@code admin-console} {@link RegisteredClient} so freshly minted
 * elevation tokens honour the new lifetime without a restart.
 */
@Service
@RequiredArgsConstructor
public class AdminPortalSettingsServiceImpl implements AdminPortalSettingsService {

    private final AdminPortalSettingsRepository repository;
    private final RegisteredClientRepository registeredClients;

    @Override
    @Transactional(readOnly = true)
    public AdminPortalSettingsData get() {
        return toData(load());
    }

    @Override
    @Transactional
    public AdminPortalSettingsData update(AdminPortalSettingsRequest request) {
        AdminPortalSettings settings = load();
        settings.update(request.reauthIntervalMinutes(), request.elevationTokenTtlMinutes(),
                request.sessionIdleTimeoutMinutes(), request.sessionAbsoluteLifetimeMinutes());
        AdminPortalSettings saved = repository.save(settings);

        syncElevationTokenTtl(saved.getElevationTokenTtlMinutes());

        return toData(saved);
    }

    private AdminPortalSettings load() {
        return repository.findById(AdminPortalSettings.SINGLETON_ID)
                .orElseThrow(() -> new NotFoundException("admin portal settings not initialized"));
    }

    private static AdminPortalSettingsData toData(AdminPortalSettings settings) {
        return new AdminPortalSettingsData(settings.getReauthIntervalMinutes(),
                settings.getElevationTokenTtlMinutes(), settings.getSessionIdleTimeoutMinutes(),
                settings.getSessionAbsoluteLifetimeMinutes());
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
