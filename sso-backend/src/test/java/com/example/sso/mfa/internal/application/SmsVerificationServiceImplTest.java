package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.SmsSender;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import com.example.sso.branding.Branding;
import java.util.Map;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingResolver;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import com.example.sso.tenancy.OrgContext;
import org.springframework.context.support.StaticMessageSource;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link SmsVerificationServiceImpl}: a 6-digit code, texted via {@link SmsSender} with the
 * code and TTL in the body, addressed to the caller-supplied tenant. Unlike the email path there is no
 * per-tenant template — the org is handed to the transport explicitly, so no OrgContext re-bind is needed.
 */
@ExtendWith(MockitoExtension.class)
class SmsVerificationServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String PHONE = "+14155550123";

    @Mock
    SmsSender sms;

    private final CodeDeliveryStatus deliveryStatus = mock(CodeDeliveryStatus.class);
    private final BrandingResolver branding = mock(BrandingResolver.class);
    private final OrgContext orgContext = mock(OrgContext.class);
    private final StaticMessageSource messages = new StaticMessageSource();

    @BeforeEach
    void registerTemplate() {
        messages.addMessage("mfa.sms.verification.text", Locale.getDefault(),
                "[{0}] Verification code: {1}. Enter it within {2} minutes.");
    }

    private SmsVerificationServiceImpl service() {
        return new SmsVerificationServiceImpl(sms, deliveryStatus, branding, orgContext, messages, 10);
    }

    @Test
    void generateCodeIsSixDigits() {
        for (int i = 0; i < 100; i++) {
            assertThat(service().generateCode()).matches("\\d{6}");
        }
    }

    @Test
    void sendCodeTextsTheCodeAndTtlToTheGivenTenantAndNumber() {
        when(orgContext.callInOrg(eq(ORG), any())).thenReturn(new Branding(
                new BrandingIdentity(null, null, null, "Acme ID"), BrandingTheme.none(), Map.of()));

        service().sendCode(ORG, PHONE, "123456", "delivery-key");

        ArgumentCaptor<String> message = ArgumentCaptor.captor();
        verify(sms).send(eq(ORG), eq(PHONE), message.capture());
        // The tenant's own name leads: a code from an unknown number that names nobody is a phishing text.
        assertThat(message.getValue()).contains("Acme ID").contains("123456").contains("10 minutes");
    }

    /**
     * A tenant that has set no name still gets a message, under the deployment's own.
     *
     * <p>The resolver is stubbed with what an unbranded tenant ACTUALLY resolves to. It used to be stubbed
     * with an all-null Branding, which the resolver cannot return — every resolution bottoms out in the
     * built-in default — so the test was pinning a defensive branch that could never run, and the caller kept
     * a fallback for a contract the module already guarantees.
     */
    @Test
    void aTenantWithNoNameOfItsOwnFallsBackToTheDeploymentName() {
        when(orgContext.callInOrg(eq(ORG), any())).thenReturn(Branding.platformDefault());

        service().sendCode(ORG, PHONE, "123456", "delivery-key");

        ArgumentCaptor<String> message = ArgumentCaptor.captor();
        verify(sms).send(eq(ORG), eq(PHONE), message.capture());
        assertThat(message.getValue()).contains(Branding.platformDefault().productName());
    }

    /** Branding is read under RLS and can fail; a one-time code must go out regardless. */
    @Test
    void aBrandingLookupThatFailsDoesNotStopTheCode() {
        when(orgContext.callInOrg(eq(ORG), any())).thenThrow(new IllegalStateException("no context"));

        service().sendCode(ORG, PHONE, "123456", "delivery-key");

        verify(sms).send(eq(ORG), eq(PHONE), anyString());
    }

    /**
     * The caller here is the async exception handler, which logs and stops. Recording the failure is what lets
     * the person waiting for the code be told anything at all — a server log reaches nobody who is signing in.
     */
    @Test
    void anUndeliveredCodeIsRecordedAgainstTheAttemptAndStillPropagates() {
        doThrow(new IllegalStateException("provider refused"))
                .when(sms).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> service().sendCode(ORG, PHONE, "123456", "delivery-key"))
                .isInstanceOf(IllegalStateException.class);

        verify(deliveryStatus).markFailed("delivery-key");
    }

    /** A send with no attempt to attribute it to records nothing rather than inventing a key. */
    @Test
    void aSendWithNoDeliveryKeyRecordsNothing() {
        doThrow(new IllegalStateException("provider refused"))
                .when(sms).send(any(), anyString(), anyString());

        assertThatThrownBy(() -> service().sendCode(ORG, PHONE, "123456", null))
                .isInstanceOf(IllegalStateException.class);

        verify(deliveryStatus).markFailed(null);
    }

    /** A delivered code leaves no failure behind. */
    @Test
    void aDeliveredCodeRecordsNoFailure() {
        service().sendCode(ORG, PHONE, "123456", "delivery-key");

        verify(deliveryStatus, never()).markFailed(anyString());
    }

}
