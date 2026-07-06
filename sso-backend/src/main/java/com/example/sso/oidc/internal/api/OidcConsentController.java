package com.example.sso.oidc.internal.api;

import com.example.sso.oidc.ConsentPage;
import com.example.sso.oidc.internal.application.ConsentScopeView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Renders the custom OAuth2 authorization-consent screen, replacing the framework's whitelabel page.
 *
 * <p>The authorization endpoint redirects an authenticated resource owner here (see
 * {@link ConsentPage}) with the requesting {@code client_id}, the {@code scope}s to authorize and
 * the opaque {@code state}. This controller resolves the client and any previously-granted scopes
 * for display; the rendered form posts the user's selection straight back to {@code /oauth2/authorize},
 * so the scope input contract (checkbox {@code name="scope"}, hidden {@code client_id}/{@code state})
 * is preserved verbatim.
 */
@Controller
@RequiredArgsConstructor
public class OidcConsentController {

    private final RegisteredClientRepository registeredClients;
    private final OAuth2AuthorizationConsentService authorizationConsents;

    @GetMapping(ConsentPage.URI)
    public String consent(Principal principal, Model model,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
            @RequestParam(OAuth2ParameterNames.STATE) String state,
            @RequestParam(name = OAuth2ParameterNames.USER_CODE, required = false) String userCode) {

        RegisteredClient client = registeredClients.findByClientId(clientId);
        if (client == null) {
            // The authorization endpoint only redirects here for a valid client that requires consent;
            // a null client means the flow was entered out of band (invariant violation, not user error).
            throw new IllegalStateException("consent requested for an unknown client");
        }

        OAuth2AuthorizationConsent existing = authorizationConsents.findById(client.getId(), principal.getName());
        Set<String> alreadyGranted = existing != null ? existing.getScopes() : Collections.emptySet();

        List<ConsentScopeView> toApprove = new ArrayList<>();
        List<ConsentScopeView> previouslyGranted = new ArrayList<>();
        for (String requested : StringUtils.delimitedListToStringArray(scope, " ")) {
            // openid is implicit for OIDC and re-added by the server, so it is never shown as a choice.
            if (OidcScopes.OPENID.equals(requested)) {
                continue;
            }
            ConsentScopeView view = ConsentScopeView.of(requested);
            (alreadyGranted.contains(requested) ? previouslyGranted : toApprove).add(view);
        }

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", client.getClientName());
        model.addAttribute("state", state);
        model.addAttribute("userCode", userCode);
        model.addAttribute("principalName", principal.getName());
        model.addAttribute("scopes", toApprove);
        model.addAttribute("previouslyGrantedScopes", previouslyGranted);
        return "consent";
    }
}
