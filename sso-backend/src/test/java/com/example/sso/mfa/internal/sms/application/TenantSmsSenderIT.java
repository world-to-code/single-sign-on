package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.SmsSender;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing against a REAL database, from the context the sender is actually called in.
 *
 * <p>A one-time code is sent from an {@code @Async} thread, which carries no tenant context — and the settings
 * lookup reads a table under FORCE row-level security, so with nothing bound the tenant's own row is not merely
 * unmatched, it is INVISIBLE. The sender then sees "no gateway configured" and quietly takes the development
 * fallback, which writes the code to a log instead of texting it. Every unit test passed throughout, because a
 * mocked settings service has no RLS to be excluded by: this is only observable against Postgres.
 */
class TenantSmsSenderIT extends AbstractIntegrationTest {

    @Autowired SmsSettingsService settings;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private final List<UUID> createdOrgs = new ArrayList<>();
    private final RecordingGateway gateway = new RecordingGateway();
    private final RecordingSender fallback = new RecordingSender();

    @AfterEach
    void cleanup() {
        orgContext.runAsPlatform(() -> ownerJdbc().update("delete from sms_settings"));
        createdOrgs.forEach(organizations::delete);
        createdOrgs.clear();
    }

    /** The regression. Configured tenant, no ambient context — exactly how the async send path calls it. */
    @Test
    void aConfiguredTenantSendsThroughItsGatewayEvenWithNoAmbientOrgContext() {
        UUID org = org();
        orgContext.runInOrg(org, () -> settings.update(
                new SmsSettingsSpec(SmsProvider.SOLAPI, "KEY-1", "secret", "01099998888")));

        assertThat(orgContext.currentOrg()).as("the async thread carries no tenant").isEmpty();
        sender().send(org, "01012345678", "Your code is 123456");

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.getFirst().apiKey()).isEqualTo("KEY-1");
        assertThat(fallback.count).as("the fallback only logs the code; taking it silently drops delivery")
                .isZero();
    }

    /** And a tenant that really has none still falls back rather than failing the sign-in. */
    @Test
    void anUnconfiguredTenantStillFallsBack() {
        UUID org = org();

        sender().send(org, "01012345678", "Your code is 123456");

        assertThat(gateway.sent).isEmpty();
        assertThat(fallback.count).isEqualTo(1);
    }

    /** The platform account is readable with no context by design, and must stay so. */
    @Test
    void aPlatformSendResolvesTheGlobalGatewayWithNoContext() {
        orgContext.runAsPlatform(() -> settings.update(
                new SmsSettingsSpec(SmsProvider.SOLAPI, "PLATFORM-KEY", "secret", "01011112222")));

        sender().send(null, "01012345678", "Your code is 123456");

        assertThat(gateway.sent).hasSize(1);
        assertThat(gateway.sent.getFirst().apiKey()).isEqualTo("PLATFORM-KEY");
    }

    private TenantSmsSender sender() {
        return new TenantSmsSender(settings, orgContext, List.of(gateway), fallback);
    }

    private UUID org() {
        String slug = "smssend-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        createdOrgs.add(id);
        return id;
    }

    /** Stands in for the provider client, so the test can see WHICH account a send would have used. */
    static final class RecordingGateway implements SmsGateway {
        final List<SmsAccount> sent = new CopyOnWriteArrayList<>();

        @Override
        public SmsProvider provider() {
            return SmsProvider.SOLAPI;
        }

        @Override
        public void send(SmsAccount account, String to, String message) {
            sent.add(account);
        }
    }

    static final class RecordingSender implements SmsSender {
        volatile int count;

        @Override
        public void send(UUID orgId, String phoneNumber, String message) {
            count++;
        }
    }
}
