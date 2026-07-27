package com.example.sso.federation.internal.api;

import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.federation.internal.application.FederationPresetCatalog;
import com.example.sso.federation.internal.application.FederationPresetField;
import com.example.sso.federation.internal.application.FederationPresetProperties;
import com.example.sso.federation.internal.application.FederationPresetView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.federation.SamlConfig;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer contract of the identity-provider admin API: {@code @Valid} bounds the request shape, the write
 * endpoints stay {@code @RequireStepUp}-gated (credential-bearing) behind the mutating permission, and — the
 * load-bearing secret invariant — the response NEVER echoes the submitted client secret. Gate enforcement is
 * proven elsewhere ({@code StepUpInterceptorTest}); this pins the declarations and the contract. A standalone
 * MVC setup exercises validation in isolation from security. Mirrors {@code SmtpSettingsControllerTest}.
 */
class IdentityProviderAdminControllerTest {

    private final IdentityProviderService service = mock(IdentityProviderService.class);
    private final FederationPresetCatalog presetCatalog = new FederationPresetCatalog(
            new FederationPresetProperties(List.of(
                    new FederationPresetView("google", "Google", "https://accounts.google.com",
                            "openid email profile", List.of()),
                    new FederationPresetView("entra", "Microsoft Entra ID",
                            "https://login.microsoftonline.com/{tenant}/v2.0", "openid email profile",
                            List.of(new FederationPresetField("tenant", "Directory (tenant) ID", "a GUID"))))));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new IdentityProviderAdminController(service, presetCatalog)).build();
    }

    private String body(String displayName, String issuer, String clientId) {
        return """
                {"displayName":"%s","issuerUri":"%s","clientId":"%s","clientSecret":"s3cret",\
                "scopes":"openid email","allowJitProvisioning":true,"enabled":true}"""
                .formatted(displayName, issuer, clientId);
    }

    private void expectPutStatus(String body, int expected) throws Exception {
        mvc.perform(put("/api/admin/identity-providers/google").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        when(service.get("google")).thenReturn(view());
        expectPutStatus(body("Google", "https://accounts.google.com", "client-123"), 200);
    }

    @Test
    void aBlankDisplayNameIsRejected() throws Exception {
        // displayName is the only unconditional field left. Which of issuerUri / idpEntityId is required depends
        // on the protocol, which a field-level annotation cannot express — those are enforced (and localized) in
        // IdentityProviderServiceImpl.validate and covered by IdentityProviderServiceImplTest.
        expectPutStatus(body("", "https://accounts.google.com", "client-123"), 400);
    }

    @Test
    void aSamlBodyMapsToASamlSpecFieldForField() throws Exception {
        when(service.get("corp")).thenReturn(view());

        mvc.perform(put("/api/admin/identity-providers/corp").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Corp SSO","protocol":"SAML",\
                                "idpEntityId":"https://idp.corp.example/entity",\
                                "ssoUrl":"https://idp.corp.example/sso",\
                                "signingCertificate":"-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----",\
                                "nameIdFormat":"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",\
                                "allowJitProvisioning":true,"enabled":true}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<IdentityProviderSpec> spec = ArgumentCaptor.forClass(IdentityProviderSpec.class);
        verify(service).save(spec.capture());
        assertThat(spec.getValue().protocol()).isEqualTo(FederationProtocol.SAML);
        assertThat(spec.getValue().config()).isInstanceOf(SamlConfig.class);
        SamlConfig config = (SamlConfig) spec.getValue().config();
        // All FOUR adjacent String parameters, each with a distinct value: toSpec passes them positionally, so
        // asserting only two would let a swap of the other pair through (a certificate landing in nameIdFormat).
        assertThat(config.idpEntityId()).isEqualTo("https://idp.corp.example/entity");
        assertThat(config.ssoUrl()).isEqualTo("https://idp.corp.example/sso");
        assertThat(config.signingCertificate()).contains("BEGIN CERTIFICATE");
        assertThat(config.nameIdFormat()).isEqualTo("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent");
    }

    @Test
    void anAbsentProtocolStillMeansOidc() throws Exception {
        // A client predating the protocol field must keep working — the OIDC body above carries no protocol.
        when(service.get("google")).thenReturn(view());

        mvc.perform(put("/api/admin/identity-providers/google").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Google", "https://accounts.google.com", "client-123")))
                .andExpect(status().isOk());

        ArgumentCaptor<IdentityProviderSpec> spec = ArgumentCaptor.forClass(IdentityProviderSpec.class);
        verify(service).save(spec.capture());
        assertThat(spec.getValue().protocol()).isEqualTo(FederationProtocol.OIDC);
    }

    @Test
    void theResponseNeverEchoesTheSubmittedSecret() throws Exception {
        when(service.get("google")).thenReturn(view());

        mvc.perform(put("/api/admin/identity-providers/google").contentType(MediaType.APPLICATION_JSON)
                        .content(body("Google", "https://accounts.google.com", "client-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("s3cret"))));
    }

    @Test
    void theWriteEndpointsAreStepUpGatedBehindTheMutatingPermissionAndReadsBehindTheReadPermission()
            throws Exception {
        // A write must NEVER be gated behind a *:read permission (OWASP A01) — assert the exact permission VALUE.
        assertThat(permissionOf("save", String.class, IdentityProviderRequest.class))
                .isEqualTo(Permissions.IDENTITY_PROVIDER_WRITE);
        assertThat(permissionOf("delete", String.class)).isEqualTo(Permissions.IDENTITY_PROVIDER_WRITE);
        assertThat(permissionOf("list")).isEqualTo(Permissions.IDENTITY_PROVIDER_READ);
        assertThat(permissionOf("get", String.class)).isEqualTo(Permissions.IDENTITY_PROVIDER_READ);
        assertThat(permissionOf("presets")).isEqualTo(Permissions.IDENTITY_PROVIDER_READ);

        assertThat(isStepUpGated("save", String.class, IdentityProviderRequest.class))
                .as("save is step-up gated").isTrue();
        assertThat(isStepUpGated("delete", String.class)).as("delete is step-up gated").isTrue();
    }

    /**
     * The card catalog: {@code GET /presets} returns the configured presets with their issuer templates and
     * extra fields. The literal {@code /presets} must bind ahead of the {@code /{alias}} pattern — otherwise it
     * would resolve as {@code get("presets")} and hit the provider service instead of the catalog.
     */
    @Test
    void presetsReturnsTheCardCatalogAndBindsAheadOfTheAliasPattern() throws Exception {
        mvc.perform(get("/api/admin/identity-providers/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("google"))
                .andExpect(jsonPath("$[0].issuerTemplate").value("https://accounts.google.com"))
                .andExpect(jsonPath("$[1].id").value("entra"))
                .andExpect(jsonPath("$[1].fields[0].key").value("tenant"));

        verify(service, never()).get("presets"); // not misrouted to the {alias} handler
    }

    private IdentityProviderView view() {
        return new IdentityProviderView("google", "Google", FederationProtocol.OIDC,
                "https://accounts.google.com", "client-123", "openid email", null, null, null, null,
                true, false, true, "google");
    }

    /** The vendor tag rides the request through to the spec, so a card-created provider records its preset. */
    @Test
    void thePresetIdIsCarriedThroughToTheSpec() throws Exception {
        when(service.get("google")).thenReturn(view());

        mvc.perform(put("/api/admin/identity-providers/google").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Google","issuerUri":"https://accounts.google.com","clientId":"c",\
                                "clientSecret":"s","scopes":"openid","allowJitProvisioning":true,"enabled":true,\
                                "presetId":"google"}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<IdentityProviderSpec> spec = ArgumentCaptor.forClass(IdentityProviderSpec.class);
        verify(service).save(spec.capture());
        assertThat(spec.getValue().presetId()).isEqualTo("google");
    }

    private String permissionOf(String method, Class<?>... params) throws Exception {
        RequirePermission annotation = IdentityProviderAdminController.class.getMethod(method, params)
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).as("%s requires a permission", method).isNotNull();
        return annotation.value();
    }

    private boolean isStepUpGated(String method, Class<?>... params) throws Exception {
        return IdentityProviderAdminController.class.getMethod(method, params).isAnnotationPresent(RequireStepUp.class);
    }

    /**
     * A client that predates the field must not be able to switch address-based account matching ON by
     * omitting it — and must not be rejected for omitting it either. Absent means false.
     */
    @Test
    void omittingTheEmailLinkingFlagIsAcceptedAndMeansOff() throws Exception {
        when(service.get("google")).thenReturn(view());

        expectPutStatus(body("Google", "https://accounts.google.com", "client-123"), 200);

        ArgumentCaptor<IdentityProviderSpec> spec = ArgumentCaptor.forClass(IdentityProviderSpec.class);
        verify(service).save(spec.capture());
        assertThat(spec.getValue().linkByVerifiedEmail()).isFalse();
    }
}
