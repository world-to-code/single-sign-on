package com.example.sso.onboarding;

import com.example.sso.organization.CompanyProfile;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.sso.bootstrap.internal.TenantBaselineProvisioner;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Tenant-baseline provisioning is decoupled from the creating request: it runs on the async executor (the
 * listener will eventually move into a separate provisioning service), so a provisioning failure can never
 * fail a signup/onboarding whose org row already committed — it is logged by the listener WITH the orgId
 * and the (idempotent) provisioning can be retried later.
 */
class TenantBaselineProvisioningFailureIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    @MockitoBean
    SessionPolicyService sessionPolicies;

    private final ListAppender<ILoggingEvent> provisionerLog = new ListAppender<>();
    private final Logger provisionerLogger = (Logger) LoggerFactory.getLogger(TenantBaselineProvisioner.class);

    private UUID orgId;

    @BeforeEach
    void attachAppender() {
        provisionerLog.start();
        provisionerLogger.addAppender(provisionerLog);
    }

    @AfterEach
    void tearDown() {
        provisionerLogger.detachAppender(provisionerLog);
        if (orgId != null) {
            organizations.delete(orgId);
        }
    }

    @Test
    void aBaselineProvisioningFailureDoesNotFailOrganizationCreation() {
        doThrow(new IllegalStateException("session policy store down"))
                .when(sessionPolicies).provisionDefault(any());

        assertThatCode(() -> {
            OrganizationView org = organizations.create(
                    new NewOrganization("octatco-orphan-it", "Octatco Orphan IT", CompanyProfile.empty()));
            orgId = org.id();
        }).doesNotThrowAnyException();

        // The listener did run (and fail) on the async executor — the org itself stays created and usable.
        verify(sessionPolicies, timeout(5000)).provisionDefault(orgId);
        assertThat(organizations.findView(orgId)).isPresent();
        // The failure is OBSERVABLE: the listener logs ERROR with the affected orgId (not a silent orphan).
        await().untilAsserted(() -> assertThat(provisionerLog.list)
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains(orgId.toString())));
    }

    @Test
    void provisioningRunsOffTheCreatingRequestThread() {
        AtomicReference<Thread> provisioningThread = new AtomicReference<>();
        doAnswer(invocation -> {
            provisioningThread.set(Thread.currentThread());
            return null;
        }).when(sessionPolicies).provisionDefault(any());

        OrganizationView org = organizations.create(
                new NewOrganization("octatco-async-it", "Octatco Async IT", CompanyProfile.empty()));
        orgId = org.id();

        verify(sessionPolicies, timeout(5000)).provisionDefault(orgId);
        assertThat(provisioningThread.get()).isNotSameAs(Thread.currentThread());
    }
}
