package com.example.sso.admin.internal.client.application;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationDescriptor;
import com.example.sso.portal.application.ApplicationSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes registered OIDC clients as launchable applications. The first-party admin console launches the admin SPA
 * ({@code /admin}); other clients launch via their {@code initiate_login_uri} (OIDC Core §4 third-party-initiated
 * login, with the required {@code iss}) or, failing that, their origin (derived from the first redirect URI).
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
        List<ClientView> clientList = new ArrayList<>(clients.listClients());
        // The first-party admin console is a GLOBAL platform app EVERY admin (super OR tenant) can launch — but
        // the tier-scoped client list omits it under a tenant tier, so add it back so the admin dashboard tile
        // appears in every tenant's portal, not only the platform host.
        if (clientList.stream().noneMatch(c -> AdminPortalSeeder.CLIENT_ID.equals(c.clientId()))) {
            clients.firstPartyConsole().ifPresent(clientList::add);
        }
        return clientList.stream().map(this::toDescriptor).toList();
    }

    private ApplicationDescriptor toDescriptor(ClientView client) {
        boolean isSystem = AdminPortalSeeder.CLIENT_ID.equals(client.clientId());
        String name = isSystem ? "Admin Portal"
                : StringUtils.hasText(client.clientName()) ? client.clientName() : client.clientId();
        String launchUrl = isSystem ? "/admin" : launchUrl(client);
        return new ApplicationDescriptor(AppType.OIDC, client.id(), name, launchUrl, isSystem);
    }

    private String launchUrl(ClientView client) {
        if (StringUtils.hasText(client.initiateLoginUri())) {
            String uri = client.initiateLoginUri()
                    .trim();
            String sep = uri.contains("?") ? "&" : "?";
            return uri + sep + "iss=" + UriUtils.encodeQueryParam(issuer, StandardCharsets.UTF_8);
        }

        String first = client.redirectUris() == null ? "" : client.redirectUris()
                .split("[,\\s]+")[0].trim();
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
