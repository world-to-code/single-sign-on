package com.example.sso.federation.internal.application;

import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application-layer tenant scoping of {@link IdentityProviderService} on real Postgres (RLS enforced through
 * the service's own transactions): a tenant registers a provider in isolation, another tenant neither sees it
 * nor can address it, the platform tier's providers stay invisible to tenants, the stored client secret is
 * ciphertext (never plaintext, never in the view), and a bound-but-orgless non-platform caller cannot write the
 * global tier. Mirrors {@code SmtpSettingsTenantScopeIT}.
 */
class IdentityProviderTenantScopeIT extends AbstractIntegrationTest {

    private static final String ISSUER_A = "https://accounts.google.com";
    private static final String ISSUER_GLOBAL = "https://login.microsoftonline.com/common/v2.0";

    @Autowired
    IdentityProviderService providers;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        // Org delete cascades its own identity_provider rows. The platform-tier rows (org_id NULL) never cascade,
        // so a test that writes them cleans up explicitly via the owner connection.
        ownerJdbc().update("delete from identity_provider where org_id is null");
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private UUID org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private IdentityProviderSpec spec(String alias, String issuer, String secret) {
        return new IdentityProviderSpec(alias, "Corp IdP", issuer, "client-id", secret, "openid email", true, false, true);
    }

    @Test
    void aTenantsProviderIsInvisibleToAnotherTenant() {
        orgA = org("idp-a");
        orgB = org("idp-b");

        orgContext.runInOrg(orgA, () -> providers.save(spec("google", ISSUER_A, "a-secret")));

        assertThat(orgContext.callInOrg(orgA, () -> providers.list())).singleElement()
                .satisfies(v -> assertThat(v.alias()).isEqualTo("google"));
        // Org B has no providers and must NEVER surface org A's — even under the same alias.
        assertThat(orgContext.callInOrg(orgB, () -> providers.list())).isEmpty();
        assertThatThrownBy(() -> orgContext.callInOrg(orgB, () -> providers.get("google")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void thePlatformTiersProvidersStayInvisibleToTenants() {
        orgA = org("idp-a");
        orgContext.runAsPlatform(() -> providers.save(spec("azure", ISSUER_GLOBAL, "g-secret")));

        // The federation registry is strictly per-tier: a tenant does NOT inherit the platform tier's providers
        // (unlike SMTP, whose global row is a send-time fallback).
        assertThat(orgContext.callInOrg(orgA, () -> providers.list())).isEmpty();
        assertThat(orgContext.callAsPlatform(() -> providers.list())).hasSize(1);
    }

    @Test
    void theStoredSecretIsCiphertextAndTheViewNeverCarriesIt() {
        orgA = org("idp-a");
        orgContext.runInOrg(orgA, () -> providers.save(spec("google", ISSUER_A, "a-secret")));

        String stored = ownerJdbc().queryForObject(
                "select client_secret_encrypted from identity_provider where org_id = ?", String.class, orgA);
        assertThat(stored).startsWith("encg:").doesNotContain("a-secret"); // SecretCipher ciphertext at rest

        assertThat(orgContext.callInOrg(orgA, () -> providers.get("google").toString()))
                .contains("google").doesNotContain("a-secret").doesNotContain(stored);
    }

    @Test
    void savedConfigRoundTripsScopesAndBooleansThroughTheDatabase() {
        orgA = org("idp-a");
        // Scopes WITHOUT openid + asymmetric booleans: proves openid-injection, scope preservation, and that the
        // two adjacent booleans survive spec→DB→view without a swap or a forced value.
        orgContext.runInOrg(orgA, () -> providers.save(
                new IdentityProviderSpec("google", "Google", ISSUER_A, "client-id", "a-secret", "email profile",
                        false, false, false)));

        IdentityProviderView view = orgContext.callInOrg(orgA, () -> providers.get("google"));
        assertThat(view.scopes()).isEqualTo("openid email profile"); // openid injected at the front, rest preserved
        assertThat(view.allowJitProvisioning()).isFalse();
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void deletingATenantsProviderRemovesIt() {
        orgA = org("idp-a");
        orgContext.runInOrg(orgA, () -> providers.save(spec("google", ISSUER_A, "a-secret")));

        orgContext.runInOrg(orgA, () -> providers.delete("google"));

        assertThat(orgContext.callInOrg(orgA, () -> providers.list())).isEmpty();
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalTier() {
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> providers.save(spec("x", ISSUER_A, "s"))))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * The address-matching opt-in has to survive the whole config chain, and the three adjacent booleans have
     * to be asymmetric, or a swap between them is undetectable. Asserts the PERSISTED column too: reading it
     * back only through the view would pass even if the write forced a value.
     */
    @Test
    void theEmailLinkingOptInSurvivesTheRoundTripAndKeepsItsOwnValue() {
        UUID org = org("idp-optin-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        orgContext.runInOrg(org, () -> providers.save(new IdentityProviderSpec("okta", "Okta", ISSUER_A,
                "client-id", "a-secret", "openid email", true, true, false)));

        IdentityProviderView view = orgContext.callInOrg(org, () -> providers.get("okta"));
        assertThat(view.linkByVerifiedEmail()).isTrue();
        assertThat(view.allowJitProvisioning()).isTrue();
        assertThat(view.enabled()).isFalse(); // asymmetric: a swap with either neighbour shows up here

        Boolean stored = ownerJdbc().queryForObject(
                "select link_by_verified_email from identity_provider where org_id = ? and alias = 'okta'",
                Boolean.class, org);
        assertThat(stored).isTrue();
    }
}
