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

    private final SmsDeliveryStatus deliveryStatus = mock(SmsDeliveryStatus.class);

    private SmsVerificationServiceImpl service() {
        return new SmsVerificationServiceImpl(sms, deliveryStatus, 10);
    }

    @Test
    void generateCodeIsSixDigits() {
        for (int i = 0; i < 100; i++) {
            assertThat(service().generateCode()).matches("\\d{6}");
        }
    }

    @Test
    void sendCodeTextsTheCodeAndTtlToTheGivenTenantAndNumber() {
        service().sendCode(ORG, PHONE, "123456", "delivery-key");

        ArgumentCaptor<String> message = ArgumentCaptor.captor();
        verify(sms).send(eq(ORG), eq(PHONE), message.capture());
        assertThat(message.getValue()).contains("123456").contains("10 minutes");
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
