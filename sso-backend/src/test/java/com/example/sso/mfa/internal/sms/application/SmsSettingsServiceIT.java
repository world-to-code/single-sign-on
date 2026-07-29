package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-tenant SMS credentials against a real database — the properties that make this feature safe to have at
 * all, none of which a mocked store can show.
 *
 * <p>The API secret is a live credential for somebody's paid account. It has to be unreadable in the table,
 * absent from every view, and unreachable from another tenant's context; and it has to be DECRYPTABLE, since
 * signing an outbound request needs the secret rather than a hash of it — which is exactly why the first three
 * matter so much.
 */
class SmsSettingsServiceIT extends AbstractIntegrationTest {

    @Autowired SmsSettingsService service;
    @Autowired List<SmsGateway> gateways;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> ownerJdbc().update("delete from sms_settings"));
        createdOrgs.forEach(organizations::delete);
        createdOrgs.clear();
    }

    @Test
    void theApiSecretIsNeverStoredInPlaintextAndHasNoFieldToLeaveBy() {
        UUID org = org();
        orgContext.runInOrg(org, () -> service.update(spec("live-secret-value")));

        String stored = orgContext.callAsPlatform(() -> ownerJdbc().queryForObject(
                "select api_secret_encrypted from sms_settings where org_id = ?", String.class, org));
        assertThat(stored).doesNotContain("live-secret-value").startsWith("encg:");

        SmsSettingsView view = orgContext.callInOrg(org, service::get);
        assertThat(view.configured()).isTrue();
        assertThat(view.apiKey()).isEqualTo("KEY-1"); // the public half is shown
        // The absence is structural, not a matter of remembering to omit it when building the view.
        assertThat(SmsSettingsView.class.getRecordComponents())
                .extracting(RecordComponent::getName).doesNotContain("apiSecret");
    }

    @Test
    void theResolvedAccountCarriesTheDecryptedSecretForSigning() {
        UUID org = org();
        orgContext.runInOrg(org, () -> service.update(spec("live-secret-value")));

        SmsAccount account = orgContext.callInOrg(org, () -> service.resolve(org)).orElseThrow();

        assertThat(account.apiSecret()).isEqualTo("live-secret-value");
        assertThat(account.provider()).isEqualTo(SmsProvider.SOLAPI);
    }

    /**
     * One tenant's paid account must be unreachable from another's context — at send time as much as on-screen.
     *
     * <p>What holds this is the RLS policy, not the {@code org_id} predicate in the query: replacing the whole
     * resolve with an unfiltered {@code findAll()} still returns nothing here, because the other tenant's row is
     * not visible to this connection at all. That is the intended division of labour — the predicate picks the
     * right row, Postgres decides which rows exist.
     */
    @Test
    void aTenantNeverSendsThroughAnotherTenantsGateway() {
        UUID first = org();
        UUID second = org();
        orgContext.runInOrg(first, () -> service.update(spec("first-secret")));

        assertThat(orgContext.callInOrg(second, service::get).configured()).isFalse();
        assertThat(orgContext.callInOrg(second, () -> service.resolve(second))).isEmpty();
    }

    /**
     * An unconfigured tenant INHERITS the platform account at send time — but its settings page shows
     * "not configured", not the platform row. Showing it as the tenant's own would invite an admin to edit
     * the deployment-wide credential believing it to be theirs, and the write would land on their own row
     * anyway, silently forking it.
     */
    @Test
    void aTenantInheritsThePlatformAccountToSendWithButNeverAsItsOwnConfig() {
        UUID tenant = org();
        orgContext.runAsPlatform(() -> service.update(
                new SmsSettingsSpec(SmsProvider.TWILIO, "PLATFORM-SID", "platform-secret", "+15550000000")));

        assertThat(orgContext.callInOrg(tenant, service::get).configured()).isFalse();

        SmsAccount inherited = orgContext.callInOrg(tenant, () -> service.resolve(tenant)).orElseThrow();
        assertThat(inherited.apiKey()).isEqualTo("PLATFORM-SID");
        assertThat(inherited.apiSecret()).isEqualTo("platform-secret");
    }

    /**
     * A blank secret on an update KEEPS the stored one. The console is never given the secret back, so a save
     * that only changes the sender number would otherwise wipe a credential it never held.
     */
    @Test
    void anUpdateThatOmitsTheSecretKeepsTheStoredOne() {
        UUID org = org();
        orgContext.runInOrg(org, () -> service.update(spec("original-secret")));

        orgContext.runInOrg(org, () -> service.update(
                new SmsSettingsSpec(SmsProvider.SOLAPI, "KEY-1", "  ", "01099998888")));

        SmsAccount account = orgContext.callInOrg(org, () -> service.resolve(org)).orElseThrow();
        assertThat(account.apiSecret()).isEqualTo("original-secret");
        assertThat(account.senderNumber()).isEqualTo("01099998888");
    }

    /** And a FIRST save with no secret is refused, rather than storing a gateway that cannot authenticate. */
    @Test
    void aFirstSaveWithoutASecretIsRefused() {
        UUID org = org();

        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> service.update(spec(" "))))
                .isInstanceOf(BadRequestException.class);
    }

    /** A bound-but-orgless non-platform caller cannot edit the platform default — deny by default. */
    @Test
    void onlyThePlatformTierMayWriteTheGlobalGateway() {
        assertThatThrownBy(() -> service.update(spec("secret"))).isInstanceOf(ForbiddenException.class);
    }

    /**
     * The routing map rests on one client per provider. A provider a tenant can SELECT but this build has no
     * client for is a setting that saves happily and then refuses every send at sign-in time.
     */
    @Test
    void everyProviderATenantCanStoreHasAClientInThisBuild() {
        assertThat(gateways).extracting(SmsGateway::provider)
                .containsExactlyInAnyOrder(SmsProvider.values());
    }

    private SmsSettingsSpec spec(String secret) {
        return new SmsSettingsSpec(SmsProvider.SOLAPI, "KEY-1", secret, "01012345678");
    }

    private UUID org() {
        String slug = "sms-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        createdOrgs.add(id);
        return id;
    }
}
