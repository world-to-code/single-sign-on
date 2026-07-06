package com.example.sso.oidc.internal.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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

    private final RegisteredClientRepository registeredClients;
    private final OAuth2AuthorizationConsentService authorizationConsents;

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
            ConsentScopeView view = ConsentScopeView.of(requested);
            (alreadyGranted.contains(requested) ? previouslyGranted : toApprove).add(view);
        }

        return new ConsentPageModel(client.getClientName(), toApprove, previouslyGranted);
    }
}
