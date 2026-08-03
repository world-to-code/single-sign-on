package com.example.sso.oidc;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The model behind the SPA's consent screen. The screen used to be a Thymeleaf page and this test used to
 * assert its HTML; the form contract now belongs to the SPA (`Consent.test.tsx`) and what is left here is
 * what the backend still decides: who is asking, where they will send the user, which scopes still need
 * approval, and whether an unfinished login can read any of it.
 *
 * <p>The localization case is the one that motivated the move. Scope descriptions come from the message
 * bundle resolved against {@code Accept-Language}, which the SPA sends as the user's CHOSEN language — so
 * the consent screen now follows the same language toggle as every other screen instead of being the one
 * page in the product that could not switch.
 */
@AutoConfigureMockMvc
class ConsentModelIT extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "consent-demo";
    private static final String REQUESTED = "openid profile email offline_access";

    @Autowired
    MockMvc mvc;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    OAuth2AuthorizationConsentService consents;

    @BeforeEach
    void ensureClient() {
        if (clients.findByClientId(CLIENT_ID) == null) {
            clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(CLIENT_ID)
                    .clientName("Grafana")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://grafana.acme.io/login/oauth2/code/demo")
                    .scope(OidcScopes.OPENID)
                    .scope(OidcScopes.PROFILE)
                    .scope(OidcScopes.EMAIL)
                    .scope("offline_access")
                    .clientSettings(ClientSettings.builder().requireAuthorizationConsent(true).build())
                    .build());
        }
    }

    private ResultActions model(String username, String acceptLanguage) throws Exception {
        return mvc.perform(get(ConsentPage.API)
                .param("client_id", CLIENT_ID)
                .param("scope", REQUESTED)
                .header("Accept-Language", acceptLanguage)
                // Only reachable once the auth policy is satisfied; the chain gates it on MFA_COMPLETE.
                .with(user(username).authorities(new SimpleGrantedAuthority(Factors.MFA_COMPLETE))));
    }

    @Test
    void theModelNamesTheClientAndWhereItWillSendYou() throws Exception {
        model("alice", "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientName").value("Grafana"))
                // A consent screen that hides the destination is complicit in phishing.
                .andExpect(jsonPath("$.redirectHost").value("grafana.acme.io"))
                .andExpect(jsonPath("$.thirdParty").value(false));
    }

    /** {@code openid} is implicit for OIDC and re-added by the authorization server — never a toggle. */
    @Test
    void openidIsNeverOfferedForApproval() throws Exception {
        model("alice", "en")
                .andExpect(jsonPath("$.toApprove[*].scope").value(
                        containsInAnyOrder("profile", "email", "offline_access")))
                .andExpect(jsonPath("$.previouslyGranted").isEmpty());
    }

    /** Scopes the user already approved are shown as context, not asked for again. */
    @Test
    void alreadyGrantedScopesAreSeparatedFromWhatIsStillAsked() throws Exception {
        String registrationId = clients.findByClientId(CLIENT_ID).getId();
        consents.save(OAuth2AuthorizationConsent.withId(registrationId, "carol")
                .scope(OidcScopes.PROFILE)
                .build());

        model("carol", "en")
                .andExpect(jsonPath("$.previouslyGranted[*].scope").value(
                        contains("profile")))
                .andExpect(jsonPath("$.toApprove[*].scope").value(
                        containsInAnyOrder("email", "offline_access")));
    }

    /** The language the SPA is displaying is the language the descriptions arrive in. */
    @Test
    void scopeDescriptionsFollowTheRequestedLanguage() throws Exception {
        model("alice", "en").andExpect(jsonPath("$.toApprove[?(@.scope=='email')].description")
                .value(contains("Your email address")));
        model("alice", "ko").andExpect(jsonPath("$.toApprove[?(@.scope=='email')].description")
                .value(contains("이메일 주소")));
    }

    /**
     * The model names a client and reveals which scopes this account already granted it, so a login that
     * has not cleared its factors must not be able to read it — the same bar the grant it feeds enforces.
     */
    @Test
    void anUnfinishedLoginCannotReadIt() throws Exception {
        mvc.perform(get(ConsentPage.API)
                        .param("client_id", CLIENT_ID)
                        .param("scope", REQUESTED)
                        .with(user("alice")))
                .andExpect(status().isForbidden());
    }
}
