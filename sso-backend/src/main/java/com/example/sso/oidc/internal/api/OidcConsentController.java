package com.example.sso.oidc.internal.api;

import com.example.sso.oidc.ConsentPage;
import com.example.sso.oidc.internal.application.ConsentModelService;
import com.example.sso.oidc.internal.application.ConsentPageModel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

/**
 * Renders the custom OAuth2 authorization-consent screen, replacing the framework's whitelabel page.
 *
 * <p>The authorization endpoint redirects an authenticated resource owner here (see
 * {@link ConsentPage}) with the requesting {@code client_id}, the {@code scope}s to authorize and
 * the opaque {@code state}. This thin adapter binds those params, delegates client/scope resolution
 * to {@link ConsentModelService}, and shapes the view model; the rendered form posts the user's
 * selection straight back to {@code /oauth2/authorize}, so the scope input contract (checkbox
 * {@code name="scope"}, hidden {@code client_id}/{@code state}) is preserved verbatim.
 */
@Controller
@RequiredArgsConstructor
public class OidcConsentController {

    private final ConsentModelService consentModel;

    @GetMapping(ConsentPage.URI)
    public String consent(Principal principal, Model model,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
            @RequestParam(OAuth2ParameterNames.STATE) String state,
            @RequestParam(name = OAuth2ParameterNames.USER_CODE, required = false) String userCode) {

        ConsentPageModel page = consentModel.build(clientId, principal.getName(), scope);

        model.addAttribute("clientId", clientId);
        model.addAttribute("clientName", page.clientName());
        model.addAttribute("redirectHost", page.redirectHost());
        model.addAttribute("thirdParty", page.thirdParty());
        model.addAttribute("state", state);
        model.addAttribute("userCode", userCode);
        model.addAttribute("principalName", principal.getName());
        model.addAttribute("scopes", page.toApprove());
        model.addAttribute("previouslyGrantedScopes", page.previouslyGranted());
        return "consent";
    }
}
