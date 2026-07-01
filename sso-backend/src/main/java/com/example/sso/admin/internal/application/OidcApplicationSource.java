package com.example.sso.admin.internal.application;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationDescriptor;
import com.example.sso.portal.ApplicationSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Publishes registered OIDC clients as launchable applications. The first-party admin console
 * launches the admin SPA ({@code /admin}); other clients launch via their {@code initiate_login_uri}
 * (OIDC Core §4 third-party-initiated login, with the required {@code iss}) or, failing that, their
 * origin (derived from the first redirect URI).
 */
@Component
public class OidcApplicationSource implements ApplicationSource {

    private final ClientAdminService clients;
    private final String issuer;

    public OidcApplicationSource(ClientAdminService clients, @Value("${sso.issuer}") String issuer) {
        this.clients = clients;
        this.issuer = issuer;
    }

    @Override
    public List<ApplicationDescriptor> applications() {
        return clients.listClients().stream().map(client -> {
            boolean system = AdminPortalSeeder.CLIENT_ID.equals(client.clientId());
            String name = system ? "Admin Portal"
                    : (client.clientName() == null || client.clientName().isBlank() ? client.clientId() : client.clientName());
            String launchUrl = system ? "/admin" : launchUrl(client);
            return new ApplicationDescriptor(AppType.OIDC, client.id(), name, launchUrl, system);
        }).toList();
    }

    private String launchUrl(ClientView client) {
        if (StringUtils.hasText(client.initiateLoginUri())) {
            String uri = client.initiateLoginUri().trim();
            String sep = uri.contains("?") ? "&" : "?";
            return uri + sep + "iss=" + UriUtils.encodeQueryParam(issuer, StandardCharsets.UTF_8);
        }
        String first = client.redirectUris() == null ? "" : client.redirectUris().split("[,\\s]+")[0].trim();
        if (first.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(first);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
