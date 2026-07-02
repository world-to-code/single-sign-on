package com.example.sso.admin.internal.portalsettings.application;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettings;
import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettingsRepository;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

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
    public AdminPortalSettingsData update(AdminPortalSettingsData command) {
        AdminPortalSettings settings = load();
        settings.update(command.reauthIntervalMinutes(), command.elevationTokenTtlMinutes(),
                command.sessionIdleTimeoutMinutes(), command.sessionAbsoluteLifetimeMinutes(),
                normalizeCidrs(command.adminAllowedCidrs()));
        AdminPortalSettings saved = repository.save(settings);

        syncElevationTokenTtl(saved.getElevationTokenTtlMinutes());

        return toData(saved);
    }

    private AdminPortalSettings load() {
        return repository.findById(AdminPortalSettings.SINGLETON_ID)
                .orElseThrow(() -> new NotFoundException("admin portal settings not initialized"));
    }

    private AdminPortalSettingsData toData(AdminPortalSettings settings) {
        return new AdminPortalSettingsData(settings.getReauthIntervalMinutes(),
                settings.getElevationTokenTtlMinutes(), settings.getSessionIdleTimeoutMinutes(),
                settings.getSessionAbsoluteLifetimeMinutes(), splitCidrs(settings.getAdminAllowedCidrs()));
    }

    /** Trims/validates each CIDR (rejecting an invalid one, 400) and joins them for storage; blank → null. */
    private String normalizeCidrs(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return null;
        }

        List<String> cleaned = cidrs.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
        cleaned.forEach(this::validateCidr);

        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    private void validateCidr(String cidr) {
        try {
            new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid CIDR: " + cidr);
        }
    }

    private List<String> splitCidrs(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }

        return List.of(stored.split(","));
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
