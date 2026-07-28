package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.FederationProvider;
import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opensaml.saml.saml2.core.NameIDType;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the LOGIN path may see, against a real database. Each protocol has its own resolver returning its own
 * shape, and neither may answer for the other: resolving across them would hand a login path a row whose columns
 * for that protocol are all null.
 *
 * <p>Asserted in BOTH directions on purpose. A filter that wrongly excluded OIDC would take every tenant's
 * inbound federation offline, and a mocked collaborator cannot see either mistake: the store's only other test
 * subject, {@code FederationLoginServiceImplTest}, stubs this class out entirely.
 */
class FederationConfigStoreIT extends AbstractIntegrationTest {

    private static final String OIDC_ALIAS = "google";
    private static final String SAML_ALIAS = "corp";
    private static final String ISSUER = "https://accounts.google.com";
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

    @Autowired
    FederationConfigStore store;
    @Autowired
    IdentityProviderService providers;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private UUID org;

    @AfterEach
    void tearDown() {
        if (org != null) {
            organizations.delete(org); // cascades the tenant's identity_provider rows
        }
    }

    private void seedBothProtocols() {
        String slug = "fcs-" + UUID.randomUUID().toString().substring(0, 8);
        org = organizations.create(new NewOrganization(slug, slug)).id();
        orgContext.runInOrg(org, () -> {
            providers.save(IdentityProviderSpec.oidc(OIDC_ALIAS, "Google", ISSUER, "client-123", "s3cret",
                    "openid email", true, false, true));
            providers.save(IdentityProviderSpec.saml(SAML_ALIAS, "Corp SSO", "https://idp.corp.example/entity",
                    "https://idp.corp.example/sso", CERT, NameIDType.PERSISTENT, "mail", true, false, true, null));
        });
    }

    @Test
    void theSignInScreenOffersBothProtocolsTaggedWithWhichTheyAre() {
        // Now that SAML has a login path, both are offered — and each carries its protocol, because that is what
        // /start branches on to decide whether to build a discovery redirect or sign an AuthnRequest.
        seedBothProtocols();

        List<FederationProvider> offered = orgContext.callInOrg(org, () -> store.enabled(org));

        assertThat(offered).extracting(FederationProvider::alias)
                .containsExactlyInAnyOrder(OIDC_ALIAS, SAML_ALIAS);
        assertThat(offered).filteredOn(p -> p.alias().equals(SAML_ALIAS))
                .singleElement().extracting(FederationProvider::protocol).isEqualTo(FederationProtocol.SAML);
        assertThat(offered).filteredOn(p -> p.alias().equals(OIDC_ALIAS))
                .singleElement().extracting(FederationProvider::protocol).isEqualTo(FederationProtocol.OIDC);
    }

    @Test
    void aSamlProviderResolvesItsPinnedUpstreamForTheLoginPath() {
        seedBothProtocols();

        ResolvedSamlProvider resolved = orgContext.callInOrg(org, () -> store.resolveEnabledSaml(org, SAML_ALIAS));

        assertThat(resolved.upstream().entityId()).isEqualTo("https://idp.corp.example/entity");
        assertThat(resolved.upstream().ssoUrl()).isEqualTo("https://idp.corp.example/sso");
        assertThat(resolved.upstream().signingCertificate()).contains("BEGIN CERTIFICATE");
        assertThat(resolved.upstream().nameIdFormat()).isEqualTo(NameIDType.PERSISTENT);
    }

    @Test
    void eachProtocolsResolverRefusesTheOtherProtocolsAlias() {
        // The two resolvers hand back different shapes; resolving across them would mean a login path reading
        // columns that are null for that row.
        seedBothProtocols();

        assertThatThrownBy(() -> orgContext.callInOrg(org, () -> store.resolveEnabledSaml(org, OIDC_ALIAS)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> orgContext.callInOrg(org, () -> store.resolveEnabled(org, SAML_ALIAS)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anUnknownAliasIsRefusedTheSameWayAsAKnownOneOfTheWrongProtocol() {
        // The refusal must not be an oracle for which providers a tenant has registered.
        seedBothProtocols();

        assertThatThrownBy(() -> orgContext.callInOrg(org, () -> store.resolveEnabled(org, "no-such-alias")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> orgContext.callInOrg(org, () -> store.resolveEnabledSaml(org, "no-such-alias")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void startingALoginThroughTheOidcProviderStillResolvesItsDecryptedSecret() {
        // The other direction: an inverted predicate would take every tenant's inbound federation offline, and
        // this is also what proves the SAML refusal above is the filter rather than a broken store.
        seedBothProtocols();

        ResolvedProvider resolved = orgContext.callInOrg(org, () -> store.resolveEnabled(org, OIDC_ALIAS));

        assertThat(resolved.alias()).isEqualTo(OIDC_ALIAS);
        assertThat(resolved.issuerUri()).isEqualTo(ISSUER);
        assertThat(resolved.clientSecret()).isEqualTo("s3cret"); // decrypted, never the stored ciphertext
    }

    @Test
    void aDisabledProviderIsNeitherOfferedNorStartable() {
        seedBothProtocols();
        orgContext.runInOrg(org, () -> providers.save(IdentityProviderSpec.oidc(OIDC_ALIAS, "Google", ISSUER,
                "client-123", "", "openid email", true, false, false)));

        assertThat(orgContext.callInOrg(org, () -> store.enabled(org)))
                .extracting(FederationProvider::alias).containsExactly(SAML_ALIAS);
        assertThatThrownBy(() -> orgContext.callInOrg(org, () -> store.resolveEnabled(org, OIDC_ALIAS)))
                .isInstanceOf(NotFoundException.class);
    }
}
