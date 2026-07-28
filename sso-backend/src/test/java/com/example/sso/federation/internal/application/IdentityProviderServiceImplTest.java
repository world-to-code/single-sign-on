package com.example.sso.federation.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.ProviderFlags;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.ApplicationEventPublisher;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensaml.saml.saml2.core.NameIDType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IdentityProviderServiceImpl}: the client secret encrypted before persist and NEVER in a
 * view, the scope list forced to include {@code openid}, alias/issuer/SSRF validation refusing (and not
 * persisting) a bad provider, upsert-in-place with a blank secret retained, and the fail-closed read/write
 * guard denying a bound-but-orgless non-platform caller. Mirrors {@code SmtpSettingsServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class IdentityProviderServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ALIAS = "google";
    private static final String ISSUER = "https://accounts.google.com";

    @Mock
    IdentityProviderRepository repository;
    @Mock
    SecretCipher cipher;
    @Mock
    FederatedIdentityLinkStore links;
    @Mock
    UserService users;
    @Mock
    ApplicationEventPublisher events;
    @Mock
    OutboundHostValidator hostValidator;
    @Mock
    OrgContext orgContext;
    @Mock
    FederationSourceSeeder sourceSeeder;
    @Mock
    FederationSourceGrantCeiling grantCeiling;

    private final FederationPresetCatalog presets = new FederationPresetCatalog(new FederationPresetProperties(
            List.of(new FederationPresetView("google", "Google", ISSUER, "openid email profile", List.of()))));

    private IdentityProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IdentityProviderServiceImpl(repository, cipher, links, users, events, hostValidator,
                orgContext, presets, sourceSeeder, grantCeiling);
    }

    private IdentityProvider row(UUID orgId, String encryptedSecret) {
        return IdentityProvider.createOidc(orgId, ALIAS, "Google", ISSUER, "client-123", encryptedSecret,
                "openid email profile", new ProviderFlags(true, false, true, null));
    }

    private IdentityProviderSpec spec(String secret, String scopes) {
        // Three adjacent booleans, all DIFFERENT: two sharing a value makes a swap between them invisible.
        return IdentityProviderSpec.oidc(ALIAS, "Google", ISSUER, "client-123", secret, scopes, true, false, true);
    }

    // --- SAML providers: the second protocol shares the registry, not the config -----------------------

    private static final String SAML_ALIAS = "corp";
    private static final String IDP_ENTITY_ID = "https://idp.corp.example/entity";
    private static final String SSO_URL = "https://idp.corp.example/sso";
    private static final String PERSISTENT = NameIDType.PERSISTENT;
    /** A DIFFERENT trust anchor, so a certificate swap is distinguishable from a no-op re-save. */
    private static final String OTHER_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIDHzCCAgegAwIBAgIUVCzS33MSI2ssdp4EoG0ETA1EsSEwDQYJKoZIhvcNAQEL
            BQAwHzEdMBsGA1UEAwwUcm90YXRlZC5jb3JwLmV4YW1wbGUwHhcNMjYwNzI3MDIy
            OTQwWhcNMzYwNzI0MDIyOTQwWjAfMR0wGwYDVQQDDBRyb3RhdGVkLmNvcnAuZXhh
            bXBsZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALEDYt2EHyH5eHbq
            pnJGJxoC3hvXxFpWLBxNwwCxxiStm83xqI+fDZCReSi+L+TokrWxeddOVZFQOtTX
            GgAKEtqMbyt/eEZZo/WOx2LYjYLbwidlCVJedcGkEGMRlBsHdt7TbQWxaWKzGoNa
            poOxVlgbg5/p518zc/k9ivUusuBrLcNdKlWiydz0rMRoNwHtkQBWupcs1sRTIzG9
            tPTCWixAacO79OsSSuH/5lcVXrQ1pdZVi4vFxokNYbrqJWsgBFdM3jzFjxz25S9O
            IEWGyfzPrsC6vUojOOUCURvt0/oWqkFHSFVnC7h8snZkskcOYzbaMXZbDUg4P/Hq
            kIvk2zsCAwEAAaNTMFEwHQYDVR0OBBYEFKuSSQjIZUBf6BJOUE/nI0euqg3QMB8G
            A1UdIwQYMBaAFKuSSQjIZUBf6BJOUE/nI0euqg3QMA8GA1UdEwEB/wQFMAMBAf8w
            DQYJKoZIhvcNAQELBQADggEBAKtF+8JESpcL4upwUUZS7gScqTjeNNhKJXdVl6Bk
            hys/SitWE4HvvnxEanS+WvBluCo0Ipz9Qy2ck+APhcnq4exbj01NzloLcLMYuNBy
            JZZuVn1S5U7TC1SXLbQX9+6DYL+V88QS4jat9eifCHwjZ7eBnr6XaxCKO/C/OTDQ
            vuqxhKaXf+cs8TES2HC7IkdDZhG5F3ZGq85Up0po1HoJecV5wd73JldxPggggGkC
            bkJd/bVzdGgSJezq7bep7wZIjbhxzxdT3KIHBIz/PwfvHvjErsv2vBcNYlH9MB38
            W1I5mgoVm7C1egC3yEitwXrs74Yj/rr85+AI0mV+Wy4pabI=
            -----END CERTIFICATE-----
            """;
    /** The link namespace is QUALIFIED so a SAML EntityID can never collide with an OIDC issuer. */
    private static final String SAML_LINK = "saml:" + IDP_ENTITY_ID;
    private static final String EMAIL_ATTRIBUTE = "mail";
    private static final String CERT = """
                    -----BEGIN CERTIFICATE-----
                    MIIDFzCCAf+gAwIBAgIUJfPDNVgTXzIHND2TQIMTY5ai5EswDQYJKoZIhvcNAQEL
                    BQAwGzEZMBcGA1UEAwwQaWRwLmNvcnAuZXhhbXBsZTAeFw0yNjA3MjcwMTQxNTJa
                    Fw0zNjA3MjQwMTQxNTJaMBsxGTAXBgNVBAMMEGlkcC5jb3JwLmV4YW1wbGUwggEi
                    MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDPOyilKLZgvL12y53Jdb7D7dRe
                    RhBh0fo0OePW8leM0sGIxk+NCKWKwDzaIWBE5OL2UQhYu+u/cy7cCSSL4UWeVsSl
                    5SmmAWA/LdOgKp9RzCPWePcGsX4/Yx0bZn2uOspHvpw/E5g+bCvuWhP9lo41v5h2
                    mHdK6THB3umbe4NhurwfetISdGaBwaxAZZZqevQawxSkJtFbQmRW4EAPqa+jsuHY
                    9KUR3OLwRiNx5LE5COwnIy4dzmQLox5kvJbPP+m/9ke1QIOFPgmAVmTMDrnN14GM
                    54SvCAkSa/OaGV0w6MF5LjdvoiLHdUU7OsOtNoZgsIjEBY0eDt4MgpF3WXWvAgMB
                    AAGjUzBRMB0GA1UdDgQWBBRus0qNBcW5I5AvEHR6R+HgyYX4eDAfBgNVHSMEGDAW
                    gBRus0qNBcW5I5AvEHR6R+HgyYX4eDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
                    DQEBCwUAA4IBAQCBywwkKK5uRCH/qslQeNwO7l2PQ19a3SEQ+u7WQ+1ZvDghRj5U
                    eyTraD9oGcnzGGzampg558/+jPfHJd/V4fKy0b82ps6ydM5UtK4GZZQYTTQpcnbB
                    TT9OkKXliYhcKPHgUW0w1/I1RnWsnlaR5roHbwJlNM8oIos/VIjl6sEBFZub74XO
                    /pQpQY2R2vpeiYkTTFnnFvSg8+4SjWqFLNlatJD51Km8dSY56agHPJFum21svLnd
                    eiM1VI6d28FuptnrAialquL+/Mw8FgHtL+bD5a/TGv5Ibku5QdL2gujByrBARb6Q
                    hfHZeZhlXCU9WihWBVmsxW/0qn/Z3iLQGwgZ
                    -----END CERTIFICATE-----
            """;

    /**
     * JIT names the new account by the address, so a connection that allows it without saying which attribute
     * carries one cannot provision anybody. Refusing the WRITE is the point: the alternative is a refusal at
     * LOGIN, which the tenant reads as "federation is broken" long after the write that caused it.
     */
    @Test
    void samlJitWithoutAnAddressAttributeIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", IDP_ENTITY_ID,
                SSO_URL, CERT, PERSISTENT, null, true, false, true, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.emailAttributeRequired");
        verify(repository, never()).save(any());
    }

    @Test
    void aBlankAddressAttributeCountsAsAbsentRatherThanAsAName() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", IDP_ENTITY_ID,
                SSO_URL, CERT, PERSISTENT, "   ", true, false, true, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.emailAttributeRequired");
    }

    @Test
    void samlCannotStoreTheEmailLinkingFlagItCanNeverHonour() {
        // SAML assertions carry no verification, so the match branch can never fire. Storing the flag would
        // promise the tenant that existing accounts get linked on first sign-in while every user is silently
        // provisioned as a DUPLICATE — orphaning the real account's groups and roles.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", IDP_ENTITY_ID,
                SSO_URL, CERT, PERSISTENT, EMAIL_ATTRIBUTE, false, true, true, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.emailLinkingUnsupported");
        verify(repository, never()).save(any());
    }

    @Test
    void aSamlConnectionWithoutJitNeedsNoAddressAttribute() {
        // The attribute exists to serve JIT; demanding it unconditionally would refuse the perfectly ordinary
        // connection whose users are all provisioned through SCIM.
        actingIn(ORG, SAML_ALIAS, null);

        service.save(IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", IDP_ENTITY_ID, SSO_URL, CERT,
                PERSISTENT, null, false, false, true, null));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmailAttribute()).isNull();
    }

    @Test
    void theAddressAttributeIsStoredTrimmed() {
        actingIn(ORG, SAML_ALIAS, null);

        service.save(IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", IDP_ENTITY_ID, SSO_URL, CERT,
                PERSISTENT, "  mail  ", true, false, true, null));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmailAttribute()).isEqualTo("mail");
    }

    private IdentityProviderSpec samlSpec(String entityId, String ssoUrl, String cert, String nameIdFormat) {
        return IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", entityId, ssoUrl, cert, nameIdFormat,
                EMAIL_ATTRIBUTE, true, false, true, null);
    }

    /** A stored row, built the way the service stores one — trimmed — so a re-save is not mistaken for an edit. */
    private IdentityProvider samlRow(String entityId) {
        return IdentityProvider.createSaml(ORG, SAML_ALIAS, "Corp SSO", entityId, SSO_URL, CERT.trim(),
                PERSISTENT, EMAIL_ATTRIBUTE, new ProviderFlags(true, false, true, null));
    }

    private void actingIn(UUID org, String alias, IdentityProvider existing) {
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(repository.findByOrgIdAndAlias(org, alias)).thenReturn(Optional.ofNullable(existing));
    }

    @Test
    void aSamlProviderIsStoredWithItsUpstreamConfigAndNoOidcColumns() {
        actingIn(ORG, SAML_ALIAS, null);

        service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, PERSISTENT));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        IdentityProvider row = saved.getValue();
        assertThat(row.getProtocol()).isEqualTo(FederationProtocol.SAML);
        assertThat(row.getIdpEntityId()).isEqualTo(IDP_ENTITY_ID);
        assertThat(row.getSsoUrl()).isEqualTo(SSO_URL);
        assertThat(row.getNameIdFormat()).isEqualTo(NameIDType.PERSISTENT);
        // The OIDC half must be absent, not merely unused: the protocol-config CHECK constraint refuses a row
        // carrying both, so a SAML provider that kept them would fail to insert at all.
        assertThat(row.getIssuerUri()).isNull();
        assertThat(row.getClientId()).isNull();
        assertThat(row.getClientSecretEncrypted()).isNull();
        assertThat(row.getScopes()).isNull();
    }

    @Test
    void aSamlProviderWithoutAnEntityIdIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec("  ", SSO_URL, CERT, PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.entityIdRequired");
        verify(repository, never()).save(any());
    }

    @Test
    void aSamlSsoUrlMustBeAbsoluteHttps() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, "http://idp.corp.example/sso", CERT, PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.ssoUrlNotHttps");
        verify(repository, never()).save(any());
    }

    @Test
    void aSamlSsoUrlIsNotSsrfValidatedBecauseTheServerNeverFetchesIt() {
        // Deliberate asymmetry with the OIDC issuer (which this server DOES fetch for discovery/JWKS). Running
        // OutboundHostValidator here would buy nothing and would lock out an on-prem IdP behind split-horizon
        // DNS. If a future slice fetches SAML metadata server-side, that fetch must validate at fetch time.
        actingIn(ORG, SAML_ALIAS, null);

        service.save(samlSpec(IDP_ENTITY_ID, "https://sts.internal.corp/sso", CERT, PERSISTENT));

        verify(repository).save(any());
        verify(hostValidator, never()).validate(any());
    }

    @Test
    void aSigningCertificateThatDoesNotParseIsRefused() {
        // A provider whose pinned key is unusable must not exist: the ACS would have nothing to verify against.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, "not-a-certificate", PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.certificateMalformed");
        verify(repository, never()).save(any());
    }

    @Test
    void aTransientNameIdFormatIsRefused() {
        // A transient NameID is a fresh pseudonym per login, so it can never resolve to an existing link — every
        // sign-in would JIT-provision a duplicate account. Refuse the configuration rather than the 100th login.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, NameIDType.TRANSIENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.nameIdFormatUnsupported");
        verify(repository, never()).save(any());
    }

    @Test
    void anUnknownNameIdFormatIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, "urn:made:up")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.nameIdFormatUnsupported");
        verify(repository, never()).save(any());
    }

    @Test
    void repointingASamlProviderAtAnotherUpstreamRetiresTheIdentitiesTheOldOneMinted() {
        // The EntityID is the namespace a NameID is resolved in, so a colliding NameID at the new upstream must
        // not inherit the account the old one's identities resolve to.
        actingIn(ORG, SAML_ALIAS, samlRow("https://old-idp.example/entity"));
        when(links.unlinkAll(ORG, "saml:https://old-idp.example/entity", SAML_ALIAS)).thenReturn(List.of());

        service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, PERSISTENT));

        verify(links).unlinkAll(ORG, "saml:https://old-idp.example/entity", SAML_ALIAS);
    }

    @Test
    void reconfiguringASamlProviderAtTheSameUpstreamKeepsItsIdentitiesAndStillApplies() {
        IdentityProvider row = samlRow(IDP_ENTITY_ID);
        actingIn(ORG, SAML_ALIAS, row);

        service.save(samlSpec(IDP_ENTITY_ID, "https://idp.corp.example/sso2", CERT, PERSISTENT));

        verify(links, never()).unlinkAll(any(), any(), any());
        // ...and the edit is not silently discarded: a branch that skipped reconfigureSaml would drop an
        // admin's rotated certificate or moved SSO endpoint while reporting success.
        assertThat(row.getSsoUrl()).isEqualTo("https://idp.corp.example/sso2");
        assertThat(row.getNameIdFormat()).isEqualTo(NameIDType.PERSISTENT);
        assertThat(row.getIdpEntityId()).isEqualTo(IDP_ENTITY_ID);
    }

    @Test
    void anAliasMayNotSwitchProtocol() {
        // Switching would repoint a live connection at a different upstream shape, and the identities minted
        // under the old protocol would be retired against an identifier the new config no longer carries.
        actingIn(ORG, ALIAS, row(ORG, "encg:cipher"));

        assertThatThrownBy(() -> service.save(IdentityProviderSpec.saml(ALIAS, "Corp SSO", IDP_ENTITY_ID,
                SSO_URL, CERT, PERSISTENT, "mail", true, false, true, null)))
                .isInstanceOf(BadRequestException.class);
        verify(links, never()).unlinkAll(any(), any(), any());
    }

    @Test
    void aSamlProviderWithoutAnSsoUrlIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, null, CERT, PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.ssoUrlRequired");
        verify(repository, never()).save(any());
    }

    @Test
    void aMalformedSamlSsoUrlIsRefusedAsMalformedNotAsNonHttps() {
        // The two axes carry different message keys; a validator that collapsed them would name the wrong fault.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, "https://[bad", CERT, PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.ssoUrlMalformed");
        verify(repository, never()).save(any());
    }

    @Test
    void aSamlProviderWithoutASigningCertificateIsRefused() {
        // Distinct from an unparsable one: absent must not reach CertificateFactory, whose behaviour on an
        // empty stream is provider-dependent.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, null, PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.certificateRequired");
        verify(repository, never()).save(any());
    }

    @Test
    void aSecondAliasClaimingAnUpstreamThisTierAlreadyFederatesToIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, "corp-b")).thenReturn(Optional.empty());
        when(repository.findByOrgIdOrderByAlias(ORG)).thenReturn(List.of(samlRow(IDP_ENTITY_ID)));

        assertThatThrownBy(() -> service.save(IdentityProviderSpec.saml("corp-b", "Corp B", IDP_ENTITY_ID,
                SSO_URL, CERT, PERSISTENT, "mail", true, false, true, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.entityIdAlreadyRegistered");
        verify(repository, never()).save(any());
    }

    @Test
    void deletingASamlProviderRetiresTheIdentitiesKeyedOnItsEntityId() {
        // The SAML twin of deletingAProviderRetiresItsIdentities. Retiring against the OIDC issuer column here
        // would pass null and match no rows: the links would outlive the provider, and re-registering the alias
        // against an attacker-controlled EntityID would resolve them back to their old accounts.
        actingIn(ORG, SAML_ALIAS, samlRow(IDP_ENTITY_ID));
        when(links.unlinkAll(ORG, SAML_LINK, SAML_ALIAS)).thenReturn(List.of());

        service.delete(SAML_ALIAS);

        verify(links).unlinkAll(ORG, SAML_LINK, SAML_ALIAS);
        verify(repository).delete(any());
    }

    @Test
    void aSamlWriteSeedsTheSamlSourceAndNeverTheOidcOne() {
        // Each protocol gets its OWN source: an assertion's attributes must not be recorded under the OIDC
        // source, whose provenance the tenant granted to a different upstream.
        actingIn(ORG, SAML_ALIAS, null);

        service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, PERSISTENT));

        verify(sourceSeeder).ensureSource(ORG, FederationProtocol.SAML);
        verify(sourceSeeder, never()).ensureSource(ORG, FederationProtocol.OIDC);
    }

    @Test
    void anOidcWriteSeedsTheOidcSourceAndNeverTheSamlOne() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt("s3cret")).thenReturn("encg:cipher");

        service.save(spec("s3cret", "email profile"));

        verify(sourceSeeder).ensureSource(ORG, FederationProtocol.OIDC);
        verify(sourceSeeder, never()).ensureSource(ORG, FederationProtocol.SAML);
    }

    @Test
    void aSamlProviderWithoutANameIdFormatIsRefused() {
        // The guard has to sit on the DEFAULT: an unset format does not mean "no opinion", it means the upstream
        // chooses — including a transient pseudonym that JIT-provisions a duplicate account on every sign-in.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.nameIdFormatRequired");
        verify(repository, never()).save(any());
    }

    @Test
    void anEmailAddressNameIdFormatIsRefused() {
        // Joining on an address violates identity-binding: a recycled corporate address would inherit the
        // previous holder's account through an AUTHORITATIVE link, bypassing every safeguard the opt-in
        // linkByVerifiedEmail path carries (first-binding-only, never a privileged target).
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, NameIDType.EMAIL)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.nameIdFormatUnsupported");
        verify(repository, never()).save(any());
    }

    @Test
    void replacingASamlSigningCertificateRetiresTheIdentitiesItVouchedFor() {
        // The certificate IS the trust anchor, and unlike OIDC (whose keys come from the issuer's own JWKS) an
        // administrator supplies it. Swapping it repoints who may speak for this upstream just as surely as
        // changing the EntityID, so the links it vouched for must not survive.
        actingIn(ORG, SAML_ALIAS, samlRow(IDP_ENTITY_ID));
        when(links.unlinkAll(ORG, SAML_LINK, SAML_ALIAS)).thenReturn(List.of());

        service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, OTHER_CERT, PERSISTENT));

        verify(links).unlinkAll(ORG, SAML_LINK, SAML_ALIAS);
    }

    @Test
    void anOverlongEntityIdIsRefused() {
        // Unbounded, it overflows the btree key of the tier-aware unique index — a 500 instead of a 400.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(samlSpec("https://" + "x".repeat(1024), SSO_URL, CERT, PERSISTENT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("federation.provider.entityIdTooLong");
        verify(repository, never()).save(any());
    }

    // --- the registration ceiling: a provider is a standing licence to write its source's keys -------

    @Test
    void registeringAProviderIsRefusedWhenItsSourceFeedsAGrantTheActorCannotMake() {
        // Not an escalation but a FREEZE: the actor would become an author of that source, and the mapping
        // evaluator needs EVERY author to be able to assign what the source's values confer — so the tenant
        // would silently stop making those grants for everyone. Refuse the write instead.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        doThrow(ForbiddenException.of("federation.provider.grantGoverned", "department"))
                .when(grantCeiling).requireAuthorityOverSource(FederationProtocol.OIDC);

        assertThatThrownBy(() -> service.save(spec("s3cret", "email")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("federation.provider.grantGoverned");
        verify(repository, never()).save(any());
    }

    @Test
    void theCeilingIsAskedAboutTheProtocolBeingRegistered() {
        // Asking about the wrong protocol would gate an OIDC registration on the SAML source's keys and vice
        // versa — refusing writes that reach nothing, and permitting the ones that do.
        actingIn(ORG, SAML_ALIAS, null);

        service.save(samlSpec(IDP_ENTITY_ID, SSO_URL, CERT, PERSISTENT));

        verify(grantCeiling).requireAuthorityOverSource(FederationProtocol.SAML);
        verify(grantCeiling, never()).requireAuthorityOverSource(FederationProtocol.OIDC);
    }

    @Test
    void updatingAnExistingProviderIsHeldToTheSameCeiling() {
        // An update re-stamps configuredBy, so it adds the actor as an author exactly as a create does.
        actingIn(ORG, ALIAS, row(ORG, "encg:cipher"));
        doThrow(ForbiddenException.of("federation.provider.grantGoverned", "department"))
                .when(grantCeiling).requireAuthorityOverSource(FederationProtocol.OIDC);

        assertThatThrownBy(() -> service.save(spec("", "email")))
                .isInstanceOf(ForbiddenException.class);
        verify(links, never()).unlinkAll(any(), any(), any()); // refused before any side effect
    }

    @Test
    void saveEncryptsTheSecretAndPersistsToTheActingTenant() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt("s3cret")).thenReturn("encg:cipher");

        service.save(spec("s3cret", "email profile"));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getClientSecretEncrypted()).isEqualTo("encg:cipher"); // ciphertext, never plaintext
        // openid is INJECTED at the front and the caller's scopes preserved (not dropped/reordered/duplicated).
        assertThat(saved.getValue().getScopes()).isEqualTo("openid email profile");
        verify(hostValidator).validate("accounts.google.com"); // SSRF check on the issuer host
    }

    @Test
    void saveStoresAKnownPresetIdAndRejectsAnUnknownOne() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");

        service.save(IdentityProviderSpec.oidc(ALIAS, "Google", ISSUER, "c", "s", "openid", true, false, true, "google"));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPresetId()).isEqualTo("google");

        // An unknown preset is refused rather than stored as an unbounded vendor label.
        assertThatThrownBy(() -> service.save(
                IdentityProviderSpec.oidc(ALIAS, "Google", ISSUER, "c", "s", "openid", true, false, true, "evil")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void saveProvisionsTheTenantsOidcSourceProfile() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");

        service.save(spec("s3cret", "openid"));

        // The tenant's OIDC source (profile + seeded claim attributes) is ensured idempotently on the write.
        verify(sourceSeeder).ensureSource(ORG, FederationProtocol.OIDC);
    }

    @Test
    void aPlatformTierProviderProvisionsNoSourceProfile() {
        // A platform-global provider mints no per-tenant link and feeds no tenant's source, so there is no
        // org-scoped profile to create — provisioning it would be a nonsense global-tier row.
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNullAndAlias(ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");

        service.save(spec("s3cret", "openid"));

        verify(sourceSeeder, never()).ensureSource(any(), any());
    }

    @Test
    void saveRecordsTheConfiguringAdministrator() {
        UUID adminId = UUID.randomUUID();
        UserAccount actor = mock(UserAccount.class);
        when(actor.getId()).thenReturn(adminId);
        when(users.findByUsername("ada")).thenReturn(Optional.of(actor));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ada", null, List.of()));
        try {
            service.save(spec("s3cret", "openid"));
        } finally {
            SecurityContextHolder.clearContext();
        }

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getConfiguredBy()).isEqualTo(adminId); // attributable to who configured it
    }

    @Test
    void saveTreatsABlankPresetIdAsCustom() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");

        service.save(IdentityProviderSpec.oidc(ALIAS, "Google", ISSUER, "c", "s", "openid", true, false, true, "  "));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPresetId()).isNull(); // a custom connection carries no vendor tag
    }

    @Test
    void savePersistsBothBooleansDistinctlyAndDoesNotSwapThem() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");

        // Asymmetric values catch a swap of the two adjacent booleans anywhere in spec→create→entity→view.
        service.save(IdentityProviderSpec.oidc(ALIAS, "Google", ISSUER, "client-123", "s", "openid", false, false, true));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isAllowJitProvisioning()).isFalse();
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    void theViewCarriesEveryFieldExceptTheSecretWithBooleansUnswapped() {
        // Asymmetric booleans (allowJit=false, enabled=true) catch a swap in toView's two adjacent boolean args.
        IdentityProvider stored = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid email", new ProviderFlags(false, true, true, "google"));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(stored));

        IdentityProviderView view = service.get(ALIAS);

        assertThat(view.alias()).isEqualTo(ALIAS);
        assertThat(view.displayName()).isEqualTo("Google");
        assertThat(view.issuerUri()).isEqualTo(ISSUER);
        assertThat(view.clientId()).isEqualTo("client-123");
        assertThat(view.scopes()).isEqualTo("openid email");
        assertThat(view.presetId()).isEqualTo("google"); // the vendor tag flows entity → view
        // All three adjacent booleans asserted, and the fixture gives them DIFFERENT values — two sharing one
        // would make a swap between them undetectable, which is the whole point of checking them here.
        assertThat(view.allowJitProvisioning()).isFalse();
        assertThat(view.linkByVerifiedEmail()).isTrue();
        assertThat(view.enabled()).isTrue();
        // The record has no secret component at all — it cannot leak the ciphertext or plaintext.
        assertThat(view.toString()).doesNotContain("cipher").doesNotContain("s3cret");
    }

    @Test
    void getThrowsNotFoundWhenTheAliasIsAbsent() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ALIAS)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updatingAnExistingProviderReconfiguresItInPlaceRatherThanInserting() {
        IdentityProvider existing = row(ORG, "encg:old");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(cipher.encrypt("new-secret")).thenReturn("encg:new");

        service.save(spec("new-secret", "openid"));

        verify(repository, never()).save(any()); // mutated in place (dirty-checked), no second row
        assertThat(existing.getClientSecretEncrypted()).isEqualTo("encg:new");
    }

    @Test
    void saveWithoutASecretKeepsTheStoredOne() {
        IdentityProvider existing = row(ORG, "encg:kept");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        // The view never returns the secret, so an edit of other fields submits a blank one — it must NOT wipe.
        service.save(spec("  ", "openid"));

        assertThat(existing.getClientSecretEncrypted()).isEqualTo("encg:kept"); // retained, not cleared
        verify(cipher, never()).encrypt(any());
    }

    @Test
    void aBrandNewProviderMustCarryASecret() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(spec(null, "openid"))).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsANonHttpsOrMalformedIssuerAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(
                IdentityProviderSpec.oidc(ALIAS, "G", "http://accounts.google.com", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class); // not https
        assertThatThrownBy(() -> service.save(
                IdentityProviderSpec.oidc(ALIAS, "G", "not a url", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class); // malformed
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsAnSsrfIssuerHostAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("169.254.169.254");

        assertThatThrownBy(() -> service.save(
                IdentityProviderSpec.oidc(ALIAS, "G", "https://169.254.169.254", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsAMalformedAlias() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(
                IdentityProviderSpec.oidc("bad_alias", "G", ISSUER, "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class); // underscore is not URL-safe
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsABlankDisplayNameClientIdOrIssuer() {
        // The service guard is independent of the controller's @NotBlank — a non-HTTP caller bypasses that layer.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(IdentityProviderSpec.oidc(ALIAS, "  ", ISSUER, "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(IdentityProviderSpec.oidc(ALIAS, "G", ISSUER, "  ", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(IdentityProviderSpec.oidc(ALIAS, "G", "  ", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void listReturnsTheActingTiersProviders() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdOrderByAlias(ORG)).thenReturn(List.of(row(ORG, "encg:cipher")));

        assertThat(service.list()).singleElement().extracting(IdentityProviderView::alias).isEqualTo(ALIAS);
    }

    @Test
    void aBoundOrglessNonPlatformCallerSeesNoProvidersAndCannotWrite() {
        // Symmetric read/write guard: an orgless non-platform caller owns nothing and must not reach the global
        // providers as if they were its own (a cross-tier leak), nor rewrite them.
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.save(spec("s", "openid"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(ALIAS)).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).findByOrgIdIsNullOrderByAlias(); // the global tier is never resolved as "own"
        verify(repository, never()).save(any());
    }

    @Test
    void thePlatformTierResolvesTheGlobalProviders() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNullOrderByAlias()).thenReturn(List.of(row(null, "encg:cipher")));

        assertThat(service.list()).hasSize(1);
    }

    @Test
    void deleteRemovesTheActingTiersProvider() {
        IdentityProvider existing = row(ORG, "encg:cipher");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.delete(ALIAS);

        verify(repository).delete(existing);
    }

    @Test
    void deleteOfAnUnknownAliasIsASilentNoOp() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        service.delete(ALIAS); // idempotent — not a 404

        verify(repository, never()).delete(any());
    }

    @Test
    void deleteOfAMalformedAliasIsRejectedBeforeTouchingTheRepository() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.delete("bad_alias")).isInstanceOf(BadRequestException.class);
        verify(repository, never()).findByOrgIdAndAlias(any(), any());
        verify(repository, never()).delete(any());
    }

    // --- federated identities must not outlive the upstream that minted them -----------------------------

    /** Repointing an alias at a DIFFERENT IdP retires its identities: a colliding `sub` at the new upstream
     *  would otherwise inherit whichever account the old one had linked. */
    @Test
    void repointingAProviderAtAnotherUpstreamRetiresItsIdentities() {
        IdentityProvider existing = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid", new ProviderFlags(true, false, true, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.save(IdentityProviderSpec.oidc(ALIAS, "Google", "https://login.microsoftonline.test", "client-123",
                "s3cret", "openid", true, false, true));

        verify(links).unlinkAll(ORG, ISSUER, ALIAS); // the OLD issuer's identities, not the new one's
    }

    /** Under pairwise subject identifiers a client-id rotation renames every user's sub, so the old links
     *  would strand the whole tenant on the login path's fail-closed guard. */
    @Test
    void rotatingTheClientIdRetiresTheIdentitiesToo() {
        IdentityProvider existing = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid", new ProviderFlags(true, false, true, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(links.unlinkAll(ORG, ISSUER, ALIAS)).thenReturn(List.of());

        service.save(IdentityProviderSpec.oidc(ALIAS, "Google", ISSUER, "client-999", "s3cret", "openid",
                true, false, true));

        verify(links).unlinkAll(ORG, ISSUER, ALIAS);
    }


    @Test
    void editingAProviderWithoutChangingItsUpstreamKeepsItsIdentities() {
        IdentityProvider existing = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid", new ProviderFlags(true, false, true, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.save(IdentityProviderSpec.oidc(ALIAS, "Google Workspace", ISSUER, "client-123", "s3cret",
                "openid email", true, false, true));

        verify(links, never()).unlinkAll(any(), any(), any());
    }

    /**
     * Retiring an identity revokes an authentication credential. Deleting the row while the sessions it
     * authenticated are still alive is not revocation — the credential is gone and the access remains until
     * expiry, which is exactly the case an admin repoints a compromised upstream to prevent.
     */
    @Test
    void retiringIdentitiesTerminatesTheSessionsTheyAuthenticated() {
        IdentityProvider existing = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid", new ProviderFlags(true, false, true, null));
        UUID retired = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(links.unlinkAll(ORG, ISSUER, ALIAS)).thenReturn(List.of(retired));
        when(users.usernamesOf(List.of(retired))).thenReturn(List.of("ada@example.com"));

        service.delete(ALIAS);

        verify(events).publishEvent(new UserAccessChangedEvent("ada@example.com", ORG));
    }

    @Test
    void retiringNothingTerminatesNothing() {
        IdentityProvider existing = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid", new ProviderFlags(true, false, true, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(links.unlinkAll(ORG, ISSUER, ALIAS)).thenReturn(List.of());

        service.delete(ALIAS);

        verify(events, never()).publishEvent(any(UserAccessChangedEvent.class));
    }

    @Test
    void deletingAProviderRetiresItsIdentities() {
        IdentityProvider existing = IdentityProvider.createOidc(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher", "openid", new ProviderFlags(true, false, true, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.delete(ALIAS);

        verify(links).unlinkAll(ORG, ISSUER, ALIAS);
        verify(repository).delete(existing);
    }
}
