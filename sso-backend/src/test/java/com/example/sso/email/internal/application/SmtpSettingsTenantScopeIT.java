package com.example.sso.email.internal.application;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application-layer tenant scoping of {@link SmtpSettingsService} on real Postgres (RLS enforced through the
 * service's own transactions): a tenant configures its relay in isolation, another tenant neither sees it nor
 * inherits it, the platform-wide override IS inherited (own-else-global), and the stored password is ciphertext
 * — the plaintext never reaches the DB nor the masked view. Complements {@code SmtpSettingsRlsIT}, which proves
 * the same isolation one layer down at the raw {@code sso_app} connection. Lives in the {@code application}
 * package to read the package-private {@link MailServer} the sender path resolves to.
 */
class SmtpSettingsTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    SmtpSettingsService smtp;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        // Org delete cascades its own smtp_settings row. The platform-global row (org_id NULL) never cascades,
        // so a test that writes it cleans it explicitly via the owner connection.
        ownerJdbc().update("delete from smtp_settings where org_id is null");
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

    // Public IP literals as hosts: InetAddress resolves a literal offline (no DNS), so the SSRF validator passes
    // in CI while a reserved ".example" name would fail closed as unresolvable. The values only need to be public
    // and distinct — this test is about tenant scoping, not real mail delivery.
    private static final String HOST_A = "8.8.8.8";
    private static final String HOST_GLOBAL = "1.1.1.1";

    private SmtpSettingsSpec spec(String host, String password) {
        return new SmtpSettingsSpec(host, 587, "postmaster", password, "no-reply@example.com", true);
    }

    @Test
    void aTenantsRelayIsInvisibleToAndUninheritedByAnotherTenant() {
        orgA = org("smtp-a");
        orgB = org("smtp-b");

        orgContext.runInOrg(orgA, () -> smtp.update(spec(HOST_A, "a-secret")));

        // Org A resolves its OWN relay; org B — with no own row and no global override — resolves nothing and
        // must NEVER surface org A's row.
        assertThat(orgContext.callInOrg(orgA, () -> smtp.resolve(orgA)).orElseThrow().host())
                .isEqualTo(HOST_A);
        assertThat(orgContext.callInOrg(orgB, () -> smtp.resolve(orgB))).isEmpty();
        assertThat(orgContext.callInOrg(orgB, () -> smtp.get().configured())).isFalse();
    }

    @Test
    void aTenantWithNoOwnRowInheritsThePlatformOverrideButItsOwnRowWins() {
        orgA = org("smtp-a");
        orgB = org("smtp-b");
        orgContext.runInOrg(orgA, () -> smtp.update(spec(HOST_A, "a-secret")));
        orgContext.runAsPlatform(() -> smtp.update(spec(HOST_GLOBAL, "g-secret")));

        // Org B has no own row → inherits the platform override.
        assertThat(orgContext.callInOrg(orgB, () -> smtp.resolve(orgB)).orElseThrow().host())
                .isEqualTo(HOST_GLOBAL);
        // Org A has its own row → own beats global (precedence), never the platform relay.
        assertThat(orgContext.callInOrg(orgA, () -> smtp.resolve(orgA)).orElseThrow().host())
                .isEqualTo(HOST_A);
    }

    @Test
    void theStoredPasswordIsCiphertextAndTheViewNeverCarriesIt() {
        orgA = org("smtp-a");
        orgContext.runInOrg(orgA, () -> smtp.update(spec(HOST_A, "a-secret")));

        String stored = ownerJdbc().queryForObject(
                "select password_encrypted from smtp_settings where org_id = ?", String.class, orgA);
        assertThat(stored).startsWith("encg:").doesNotContain("a-secret"); // SecretCipher ciphertext at rest

        // The masked view exposes the host but no password field at all — it cannot leak the secret.
        assertThat(orgContext.callInOrg(orgA, () -> smtp.get().toString()))
                .contains(HOST_A).doesNotContain("a-secret").doesNotContain(stored);
    }

    @Test
    void deletingATenantsRowRevertsItToTheInheritedDefault() {
        orgA = org("smtp-a");
        orgContext.runInOrg(orgA, () -> smtp.update(spec(HOST_A, "a-secret")));
        assertThat(orgContext.callInOrg(orgA, () -> smtp.resolve(orgA))).isPresent();

        orgContext.runInOrg(orgA, () -> smtp.delete());

        // No own row and no platform override → resolves empty (caller falls back to the application.yml default).
        assertThat(orgContext.callInOrg(orgA, () -> smtp.resolve(orgA))).isEmpty();
        assertThat(orgContext.callInOrg(orgA, () -> smtp.get().configured())).isFalse();
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        // An org user that authenticated without a resolved tenant must not fall through to rewriting the
        // platform-wide default relay.
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> smtp.update(spec("smtp.evil.example", "x"))))
                .isInstanceOf(ForbiddenException.class);
    }
}
