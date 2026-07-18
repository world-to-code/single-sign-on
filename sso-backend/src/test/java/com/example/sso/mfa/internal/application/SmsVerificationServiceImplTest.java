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

    private SmsVerificationServiceImpl service() {
        return new SmsVerificationServiceImpl(sms, 10);
    }

    @Test
    void generateCodeIsSixDigits() {
        for (int i = 0; i < 100; i++) {
            assertThat(service().generateCode()).matches("\\d{6}");
        }
    }

    @Test
    void sendCodeTextsTheCodeAndTtlToTheGivenTenantAndNumber() {
        service().sendCode(ORG, PHONE, "123456");

        ArgumentCaptor<String> message = ArgumentCaptor.captor();
        verify(sms).send(eq(ORG), eq(PHONE), message.capture());
        assertThat(message.getValue()).contains("123456").contains("10 minutes");
    }
}
