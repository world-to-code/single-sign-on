package com.example.sso.mfa.internal.sms.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.SmsSender;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.function.Supplier;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which account an outbound code is actually sent through, and — the part that matters more — what happens
 * when the tenant's own gateway refuses it.
 */
class TenantSmsSenderTest {

    private static final UUID ORG = UUID.randomUUID();

    private SmsSettingsService settings;
    private OrgContext orgContext;
    private AuditService audit;
    private SmsGateway solapi;
    private SmsGateway twilio;
    private SmsSender fallback;
    private TenantSmsSender sender;

    @BeforeEach
    void setUp() {
        settings = mock(SmsSettingsService.class);
        // The real one binds the tenant on the connection for RLS; here it only has to run the lookup, since a
        // mocked store has no row-level security to be excluded by. TenantSmsSenderIT is what covers the binding.
        audit = mock(AuditService.class);
        orgContext = mock(OrgContext.class);
        Mockito.lenient().when(orgContext.callInOrg(any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        solapi = gateway(SmsProvider.SOLAPI);
        twilio = gateway(SmsProvider.TWILIO);
        fallback = mock(SmsSender.class);
        sender = new TenantSmsSender(settings, orgContext, audit, List.of(solapi, twilio), fallback);
    }

    @Test
    void aConfiguredTenantSendsThroughItsOwnProvidersClient() {
        SmsAccount account = new SmsAccount(SmsProvider.TWILIO, "AC-SID", "token", "+15550000000");
        when(settings.resolve(ORG)).thenReturn(Optional.of(account));

        sender.send(ORG, "+15551234567", "code");

        verify(twilio).send(account, "+15551234567", "code");
        verify(solapi, never()).send(any(), anyString(), anyString());
        verify(fallback, never()).send(any(), anyString(), anyString());
    }

    @Test
    void anUnconfiguredTenantFallsBackToTheDeploymentSender() {
        when(settings.resolve(ORG)).thenReturn(Optional.empty());

        sender.send(ORG, "01012345678", "code");

        verify(fallback).send(ORG, "01012345678", "code");
    }

    /**
     * The one that would be tempting to get wrong. The fallback logs the code in development and withholds it
     * in production, so quietly retrying through it on a gateway failure would turn "the provider rejected our
     * sender number" into "the code was never delivered and the sign-in looked fine". The failure has to reach
     * the caller.
     */
    @Test
    void aFailedTenantSendIsNotQuietlyDowngradedToTheFallback() {
        SmsAccount account = new SmsAccount(SmsProvider.SOLAPI, "KEY", "secret", "01099998888");
        when(settings.resolve(ORG)).thenReturn(Optional.of(account));
        doThrow(new IllegalStateException("provider rejected the sender number"))
                .when(solapi).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> sender.send(ORG, "01012345678", "code"))
                .isInstanceOf(IllegalStateException.class);
        verify(fallback, never()).send(any(), anyString(), anyString());
    }

    /** A provider stored by a build that had a client for it, running on one that does not. */
    @Test
    void aStoredProviderWithNoClientOnThisBuildIsRefusedRatherThanFallenBack() {
        SmsAccount account = new SmsAccount(SmsProvider.TWILIO, "AC-SID", "token", "+15550000000");
        when(settings.resolve(ORG)).thenReturn(Optional.of(account));
        sender = new TenantSmsSender(settings, orgContext, audit, List.of(solapi), fallback);

        assertThatThrownBy(() -> sender.send(ORG, "+15551234567", "code"))
                .isInstanceOf(IllegalStateException.class);
        verify(fallback, never()).send(any(), anyString(), anyString());
    }

    /** The failure message must name the provider without carrying the credentials that came with it. */
    @Test
    void theRefusalNamesTheProviderAndNothingElse() {
        SmsAccount account = new SmsAccount(SmsProvider.TWILIO, "AC-SID", "the-auth-token", "+15550000000");
        when(settings.resolve(ORG)).thenReturn(Optional.of(account));
        sender = new TenantSmsSender(settings, orgContext, audit, List.of(solapi), fallback);

        assertThatThrownBy(() -> sender.send(ORG, "+15551234567", "code"))
                .hasMessageContaining("TWILIO")
                .hasMessageNotContaining("the-auth-token")
                .hasMessageNotContaining("AC-SID");
    }

    /** The platform's own messages (no tenant in context) resolve the same way, through a null org. */
    @Test
    void aPlatformSendResolvesTheGlobalAccount() {
        SmsAccount account = new SmsAccount(SmsProvider.SOLAPI, "KEY", "secret", "01099998888");
        when(settings.resolve(null)).thenReturn(Optional.of(account));

        sender.send(null, "01012345678", "code");

        verify(solapi).send(account, "01012345678", "code");
    }

    /**
     * The lookup must run in the TENANT's context, not the platform's.
     *
     * <p>Asserted as an interaction because both choices produce the same message: platform context bypasses
     * RLS, so it would find the row and the send would look correct. What it loses is the second constraint on
     * a read of somebody's paid-account credentials — with RLS bypassed, only the explicit {@code orgId} filter
     * stands between this and another tenant's gateway. A mutation swapping the two survived every
     * outcome-based test in this file and the integration one, which is why this assertion exists.
     */
    @Test
    void theSettingsLookupRunsInTheTenantsContextNotThePlatforms() {
        when(settings.resolve(ORG)).thenReturn(Optional.of(
                new SmsAccount(SmsProvider.SOLAPI, "KEY", "secret", "01099998888")));

        sender.send(ORG, "01012345678", "code");

        verify(orgContext).callInOrg(eq(ORG), any());
        verify(orgContext, never()).callAsPlatform(any());
    }

    /**
     * A refusal is never retried. The provider already answered — the same request gets the same answer, so a
     * retry buys nothing, doubles the log noise, and on a channel billed per message is a habit worth not
     * forming.
     */
    @Test
    void aProviderRefusalIsNotRetriedAndIsAudited() {
        configuredSolapi();
        doThrow(SmsDeliveryException.refused(SmsProvider.SOLAPI, "FailedToAddMessage", "발신번호 미등록", null))
                .when(solapi).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> sender.send(ORG, "01012345678", "code"))
                .isInstanceOf(SmsDeliveryException.class);

        verify(solapi, times(1)).send(any(), anyString(), anyString());
        assertThat(recordedFailure().reason()).isEqualTo("FailedToAddMessage");
    }

    /** A connection that never opened proves nothing was sent, which is the only safe case to repeat. */
    @Test
    void aProviderThatWasNeverReachedIsRetriedOnce() {
        configuredSolapi();
        doThrow(SmsDeliveryException.unreachable(SmsProvider.SOLAPI, SmsDeliveryException.NOT_CONNECTED, null))
                .when(solapi).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> sender.send(ORG, "01012345678", "code"))
                .isInstanceOf(SmsDeliveryException.class);

        verify(solapi, times(2)).send(any(), anyString(), anyString());
    }

    /**
     * A request that went out and was never answered MAY have been accepted. Repeating it bills the tenant
     * twice and texts the person twice, so ambiguity means no retry.
     */
    @Test
    void aSendWhoseOutcomeIsUnknownIsNotRepeated() {
        configuredSolapi();
        doThrow(SmsDeliveryException.unreachable(SmsProvider.SOLAPI, SmsDeliveryException.NO_ANSWER, null))
                .when(solapi).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> sender.send(ORG, "01012345678", "code"))
                .isInstanceOf(SmsDeliveryException.class);

        verify(solapi, times(1)).send(any(), anyString(), anyString());
    }

    /** A retry that succeeds is a delivered code, so nothing is audited as a failure. */
    @Test
    void aSuccessfulRetryLeavesNoFailureBehind() {
        configuredSolapi();
        doThrow(SmsDeliveryException.unreachable(SmsProvider.SOLAPI, SmsDeliveryException.NOT_CONNECTED, null))
                .doNothing()
                .when(solapi).send(any(), anyString(), anyString());

        sender.send(ORG, "01012345678", "code");

        verify(solapi, times(2)).send(any(), anyString(), anyString());
        verify(audit, never()).record(any());
    }

    /** The audit row must name the provider and its code, never the provider's own message text. */
    @Test
    void theAuditRecordsTheCodeAndTheTenantNotTheProvidersProse() {
        configuredSolapi();
        doThrow(SmsDeliveryException.refused(SmsProvider.SOLAPI, "FailedToAddMessage", "발신번호 미등록", null))
                .when(solapi).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> sender.send(ORG, "01012345678", "code")).isInstanceOf(RuntimeException.class);

        AuditRecord record = recordedFailure();
        assertThat(record.type()).isEqualTo(AuditType.SMS_SEND_FAILED);
        assertThat(record.success()).isFalse();
        assertThat(record.orgId()).isEqualTo(ORG);
        // The provider's explanation is kept for the LOG, so this is the line that stops it drifting into the
        // stored row: neither field may carry a third party's free text.
        assertThat(record.detail()).doesNotContain("발신번호");
        assertThat(record.reason()).doesNotContain("발신번호");
    }

    private void configuredSolapi() {
        when(settings.resolve(ORG)).thenReturn(Optional.of(
                new SmsAccount(SmsProvider.SOLAPI, "KEY", "secret", "01099998888")));
    }

    private AuditRecord recordedFailure() {
        ArgumentCaptor<AuditRecord> captured = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(captured.capture());
        return captured.getValue();
    }

    private SmsGateway gateway(SmsProvider provider) {
        SmsGateway gateway = mock(SmsGateway.class);
        Mockito.lenient().when(gateway.provider()).thenReturn(provider);
        return gateway;
    }
}
