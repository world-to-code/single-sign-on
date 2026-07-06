package com.example.sso.oidc.internal.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the consent view-model assembly: the three scope buckets (openid dropped, freshly
 * requested → to-approve, already-consented → previously-granted) and the unknown-client invariant.
 * Drives the real bucketing logic with mocked Authorization Server services, so no web/DB layer is
 * needed. The adversary is a scope split that silently mis-buckets or an openid toggle leaking to the UI.
 */
@ExtendWith(MockitoExtension.class)
class ConsentModelServiceTest {

    private static final String CLIENT_ID = "demo-client";
    private static final String PRINCIPAL = "alice";

    @Mock
    private RegisteredClientRepository registeredClients;
    @Mock
    private OAuth2AuthorizationConsentService authorizationConsents;

    @InjectMocks
    private ConsentModelService service;

    private RegisteredClient client() {
        return RegisteredClient.withId("cid-1")
                .clientId(CLIENT_ID)
                .clientName("Demo OIDC Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://example.com/cb")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .build();
    }

    private OAuth2AuthorizationConsent consentWithScopes(RegisteredClient client, String... scopes) {
        OAuth2AuthorizationConsent.Builder builder = OAuth2AuthorizationConsent.withId(client.getId(), PRINCIPAL);
        for (String scope : scopes) {
            builder.scope(scope);
        }
        return builder.build();
    }

    @Test
    void openidIsNeverOfferedAsAToggle() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile");

        assertThat(scopeNames(page.toApprove())).containsExactly(OidcScopes.PROFILE);
        assertThat(scopeNames(page.previouslyGranted())).isEmpty();
        assertThat(page.clientName()).isEqualTo("Demo OIDC Client");
    }

    @Test
    void aFreshlyRequestedScopeGoesToApprove() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile email");

        assertThat(scopeNames(page.toApprove())).containsExactlyInAnyOrder(OidcScopes.PROFILE, OidcScopes.EMAIL);
        assertThat(scopeNames(page.previouslyGranted())).isEmpty();
    }

    @Test
    void anAlreadyGrantedScopeGoesToPreviouslyGranted() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL))
                .thenReturn(consentWithScopes(client, OidcScopes.EMAIL));

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile email");

        // profile is new (approve), email was already granted (context), openid never shown.
        assertThat(scopeNames(page.toApprove())).containsExactly(OidcScopes.PROFILE);
        assertThat(scopeNames(page.previouslyGranted())).containsExactly(OidcScopes.EMAIL);
    }

    @Test
    void aNullPriorConsentTreatsEveryScopeAsFresh() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "profile email");

        assertThat(scopeNames(page.toApprove())).containsExactlyInAnyOrder(OidcScopes.PROFILE, OidcScopes.EMAIL);
        assertThat(scopeNames(page.previouslyGranted())).isEmpty();
    }

    @Test
    void anUnknownClientIsAnInvariantViolation() {
        when(registeredClients.findByClientId("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.build("nope", PRINCIPAL, "openid profile"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<String> scopeNames(List<ConsentScopeView> views) {
        return views.stream().map(ConsentScopeView::scope).toList();
    }
}
