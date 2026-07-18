package com.example.sso.oidc.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingResolver;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the consent view-model assembly: the three scope buckets (openid dropped, freshly
 * requested → to-approve, already-consented → previously-granted), the redirect-host / third-party
 * signals the screen shows so a destination is never hidden, and localized scope descriptions with a
 * humanized fallback. Drives the real bucketing logic with mocked Authorization Server services and a
 * real message bundle, so no web/DB layer is needed. The adversary is a scope split that silently
 * mis-buckets, an openid toggle leaking to the UI, or a hidden redirect destination.
 */
@ExtendWith(MockitoExtension.class)
class ConsentModelServiceTest {

    private static final String CLIENT_ID = "demo-client";
    private static final String PRINCIPAL = "alice";

    @Mock
    private RegisteredClientRepository registeredClients;
    @Mock
    private OAuth2AuthorizationConsentService authorizationConsents;
    @Mock
    private BrandingResolver branding;
    @Mock
    private OrgContext orgContext;

    private ConsentModelService service;

    @BeforeEach
    void setUp() {
        // A real bundle so description resolution (bundle hit + humanized fallback) is genuinely exercised.
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        service = new ConsentModelService(registeredClients, authorizationConsents, messages, branding, orgContext);
        // Default: no tenant branding — the scope-bucketing tests don't care, so keep these lenient.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        lenient().when(branding.resolve(any())).thenReturn(Branding.platformDefault());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private RegisteredClient client() {
        return RegisteredClient.withId("cid-1")
                .clientId(CLIENT_ID)
                .clientName("Demo OIDC Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://grafana.acme.io/login/oauth2/code/demo")
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
    void theRedirectHostIsDerivedFromTheFirstRedirectUri() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile");

        assertThat(page.redirectHost()).isEqualTo("grafana.acme.io");
    }

    @Test
    void everyClientIsPresentedAsOrganizationRegistered() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile");

        // No dynamic client registration exists in this IdP, so there is no reliable third-party signal.
        assertThat(page.thirdParty()).isFalse();
    }

    @Test
    void knownScopesGetALocalizedDescriptionAndUnknownOnesAreHumanized() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile custom_scope");

        assertThat(descriptionOf(page.toApprove(), OidcScopes.PROFILE))
                .isEqualTo("Your basic profile — display name and username");
        // An unknown scope falls back to its own name with separators as spaces — never blank, never raw.
        assertThat(descriptionOf(page.toApprove(), "custom_scope")).isEqualTo("custom scope");
    }

    @Test
    void theActingTenantsBrandingIsResolvedIntoTheModel() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);
        UUID org = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(branding.resolve(org)).thenReturn(new Branding("https://cdn.acme/l.png", "#7c3aed", "Acme"));

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile");

        assertThat(page.brandLogoUrl()).isEqualTo("https://cdn.acme/l.png");
        assertThat(page.brandProductName()).isEqualTo("Acme");
        assertThat(page.brandAccentTriple()).isEqualTo("262 83% 58%"); // #7c3aed → CSS HSL triple
    }

    @Test
    void theBuiltInDefaultBrandingCarriesNoLogoOrAccent() {
        RegisteredClient client = client();
        when(registeredClients.findByClientId(CLIENT_ID)).thenReturn(client);
        when(authorizationConsents.findById(client.getId(), PRINCIPAL)).thenReturn(null);
        // setUp's lenient default: currentOrg empty → resolve → platformDefault().

        ConsentPageModel page = service.build(CLIENT_ID, PRINCIPAL, "openid profile");

        assertThat(page.brandProductName()).isEqualTo("Mini SSO");
        assertThat(page.brandLogoUrl()).isNull();
        assertThat(page.brandAccentTriple()).isNull();
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

    private static String descriptionOf(List<ConsentScopeView> views, String scope) {
        return views.stream().filter(v -> v.scope().equals(scope)).map(ConsentScopeView::description)
                .findFirst().orElseThrow();
    }
}
