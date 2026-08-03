package com.example.sso.oidc.internal.api;

import com.example.sso.oidc.ConsentPage;
import com.example.sso.oidc.internal.application.ConsentModelService;
import com.example.sso.oidc.internal.application.ConsentPageModel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Serves the model behind the SPA's consent screen (see {@link ConsentPage}).
 *
 * <p>The authorization endpoint redirects the resource owner to the SPA route with the requesting
 * {@code client_id} and the {@code scope}s to authorize; the SPA calls this to learn who is asking and
 * what for, then posts the user's selection back to {@code /oauth2/authorize}. {@code state} never
 * reaches this endpoint — the SPA already holds it and hands it straight back, and it means nothing here.
 */
@RestController
@RequiredArgsConstructor
public class OidcConsentController {

    private final ConsentModelService consentModel;

    @GetMapping(ConsentPage.API)
    public ResponseEntity<ConsentPageModel> consent(Principal principal,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope) {

        return ResponseEntity.ok(consentModel.build(clientId, principal.getName(), scope));
    }
}
