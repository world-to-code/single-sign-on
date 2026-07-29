package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.example.sso.mfa.SmsSender;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.function.Supplier;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private SmsGateway solapi;
    private SmsGateway twilio;
    private SmsSender fallback;
    private TenantSmsSender sender;

    @BeforeEach
    void setUp() {
        settings = mock(SmsSettingsService.class);
        // The real one binds the tenant on the connection for RLS; here it only has to run the lookup, since a
        // mocked store has no row-level security to be excluded by. TenantSmsSenderIT is what covers the binding.
        orgContext = mock(OrgContext.class);
        Mockito.lenient().when(orgContext.callInOrg(any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        solapi = gateway(SmsProvider.SOLAPI);
        twilio = gateway(SmsProvider.TWILIO);
        fallback = mock(SmsSender.class);
        sender = new TenantSmsSender(settings, orgContext, List.of(solapi, twilio), fallback);
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
        sender = new TenantSmsSender(settings, orgContext, List.of(solapi), fallback);

        assertThatThrownBy(() -> sender.send(ORG, "+15551234567", "code"))
                .isInstanceOf(IllegalStateException.class);
        verify(fallback, never()).send(any(), anyString(), anyString());
    }

    /** The failure message must name the provider without carrying the credentials that came with it. */
    @Test
    void theRefusalNamesTheProviderAndNothingElse() {
        SmsAccount account = new SmsAccount(SmsProvider.TWILIO, "AC-SID", "the-auth-token", "+15550000000");
        when(settings.resolve(ORG)).thenReturn(Optional.of(account));
        sender = new TenantSmsSender(settings, orgContext, List.of(solapi), fallback);

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

    private SmsGateway gateway(SmsProvider provider) {
        SmsGateway gateway = mock(SmsGateway.class);
        Mockito.lenient().when(gateway.provider()).thenReturn(provider);
        return gateway;
    }
}
