package com.example.sso.oidc.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingResolver;
import com.example.sso.tenancy.OrgContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Assembles the {@link ConsentPageModel} for the consent screen from a raw authorization request:
 * resolves the requesting client and the user's prior consent, then splits the requested scopes into
 * those still needing approval versus those already granted. {@code openid} is dropped — it is implicit
 * for OIDC and re-added by the authorization server, so it is never offered as a toggle. Kept out of the
 * controller so this bucketing logic is unit-testable without the web layer.
 */
@Service
@RequiredArgsConstructor
public class ConsentModelService {

    private static final String SCOPE_DESCRIPTION_PREFIX = "consent.scope.";

    private final RegisteredClientRepository registeredClients;
    private final OAuth2AuthorizationConsentService authorizationConsents;
    private final MessageSource messageSource;
    private final BrandingResolver branding;
    private final OrgContext orgContext;

    public ConsentPageModel build(String clientId, String principalName, String requestedScope) {
        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null) {
            // The authorization endpoint only redirects here for a valid client that requires consent;
            // a null client means the flow was entered out of band (invariant violation, not user error).
            throw new IllegalStateException("consent requested for an unknown client");
        }

        OAuth2AuthorizationConsent existing = authorizationConsents.findById(client.getId(), principalName);
        Set<String> alreadyGranted = existing != null ? existing.getScopes() : Set.of();

        List<ConsentScopeView> toApprove = new ArrayList<>();
        List<ConsentScopeView> previouslyGranted = new ArrayList<>();
        for (String requested : StringUtils.delimitedListToStringArray(requestedScope, " ")) {
            if (OidcScopes.OPENID.equals(requested)) {
                continue;
            }
            ConsentScopeView view = new ConsentScopeView(requested, describe(requested));
            (alreadyGranted.contains(requested) ? previouslyGranted : toApprove).add(view);
        }

        // Third-party detection: this IdP has no dynamic or self-service client registration — every
        // RegisteredClient is provisioned by an organization administrator (seeders, or the admin
        // console's ClientAdminService behind admin authz). There is thus no reliable signal that a
        // client is a third party the organization did not register, so we honestly always present it
        // as organization-registered rather than invent metadata. The flag flips here in one place if a
        // federated or externally-registered client type is ever introduced.
        boolean thirdParty = false;

        // The acting tenant's branding for the consent screen (org bound from the request host by consent time).
        Branding brand = branding.resolve(orgContext.currentOrg().orElse(null));
        return new ConsentPageModel(client.getClientName(), redirectHost(client), thirdParty,
                toApprove, previouslyGranted, brand.logoUrl(), accentTriple(brand.accentColor()),
                brand.productName());
    }

    /**
     * Convert a {@code #RRGGBB} accent to the CSS HSL triple ({@code "H S% L%"}) the consent stylesheet's
     * {@code --accent} token uses, so a strict-hex-validated brand color overrides it inline (CSP permits
     * inline styles). Returns null for an absent/unexpected value (the default accent then stands). The input
     * is validated {@code ^#[0-9a-fA-F]{6}$} at write time, so the output is always safe digits/percent/space.
     */
    private String accentTriple(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
            return null;
        }
        try {
            double r = Integer.parseInt(hex.substring(1, 3), 16) / 255.0;
            double g = Integer.parseInt(hex.substring(3, 5), 16) / 255.0;
            double b = Integer.parseInt(hex.substring(5, 7), 16) / 255.0;
            double max = Math.max(r, Math.max(g, b));
            double min = Math.min(r, Math.min(g, b));
            double l = (max + min) / 2;
            double d = max - min;
            double s = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
            double h = 0;
            if (d != 0) {
                if (max == r) {
                    h = ((g - b) / d) % 6;
                } else if (max == g) {
                    h = (b - r) / d + 2;
                } else {
                    h = (r - g) / d + 4;
                }
                h *= 60;
                if (h < 0) {
                    h += 360;
                }
            }
            return "%d %d%% %d%%".formatted(Math.round(h), Math.round(s * 100), Math.round(l * 100));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /** The host of the client's first redirect URI, or {@code null} if it cannot be derived. */
    private String redirectHost(RegisteredClient client) {
        Set<String> redirectUris = client.getRedirectUris();
        if (redirectUris.isEmpty()) {
            return null;
        }
        try {
            return URI.create(redirectUris.iterator().next()).getHost();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Localized description for a scope, from {@code consent.scope.<name>} in the request locale, with a
     * humanized fallback (separators as spaces) so an unknown/custom scope is never blank or a raw token.
     */
    private String describe(String scope) {
        String humanized = scope.replace('_', ' ').replace('.', ' ');
        return messageSource.getMessage(SCOPE_DESCRIPTION_PREFIX + scope, null, humanized,
                LocaleContextHolder.getLocale());
    }
}
