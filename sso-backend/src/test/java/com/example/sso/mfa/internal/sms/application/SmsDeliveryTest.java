package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The judgement that decides whether a failed send may be repeated — the part worth testing, because a wrong
 * answer here is either a person who never gets their code or a tenant billed twice for one.
 */
class SmsDeliveryTest {

    private final SmsDelivery delivery = new SmsDelivery();

    /** The exact failure that sent us looking: Solapi's own code is what an operator needs to see. */
    @Test
    void aSolapiRefusalKeepsItsErrorCodeAndIsNeverRetryable() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", """
                        {"errorCode":"FailedToAddMessage","errorMessage":"발신번호 미등록"}""".getBytes(), null));

        assertThat(failure.providerCode()).isEqualTo("FailedToAddMessage");
        assertThat(failure.retryable()).isFalse();
    }

    /**
     * The provider's prose must not travel with the failure: it is a third party's free text and would land
     * verbatim in an audit row and a log line.
     */
    @Test
    void theProvidersOwnMessageTextIsNotCarried() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", """
                        {"errorCode":"FailedToAddMessage","errorMessage":"발신번호 미등록"}""".getBytes(), null));

        assertThat(failure.getMessage()).doesNotContain("발신번호").contains("FailedToAddMessage");
    }

    @Test
    void aTwilioRefusalKeepsItsNumericCode() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", """
                        {"code":21606,"message":"The From phone number is not a valid"}""".getBytes(), null));

        assertThat(failure.providerCode()).isEqualTo("21606");
    }

    /** A body in no documented shape still has to yield something an operator can act on. */
    @Test
    void aRefusalWithAnUnrecognisableBodyFallsBackToTheStatus() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", "<html>gateway error</html>".getBytes(), null));

        assertThat(failure.providerCode()).isEqualTo("HTTP 400");
        assertThat(failure.retryable()).isFalse();
    }

    /** A refused connection proves the message never went out — the one case where repeating is free of doubt. */
    @Test
    void aConnectionThatNeverOpenedIsRetryable() {
        assertThat(attemptFailing(new ResourceAccessException("I/O", new ConnectException("refused"))).retryable())
                .isTrue();
        assertThat(attemptFailing(new ResourceAccessException("I/O", new UnknownHostException("dns"))).retryable())
                .isTrue();
    }

    /**
     * A timeout does NOT prove that. The request may have been accepted and only the answer lost, so repeating
     * it texts the person twice and bills the tenant twice — ambiguity has to mean no retry.
     */
    @Test
    void aTimeoutIsTreatedAsUnknownRatherThanUndelivered() {
        SmsDeliveryException failure =
                attemptFailing(new ResourceAccessException("I/O", new SocketTimeoutException("Read timed out")));

        assertThat(failure.providerCode()).isEqualTo(SmsDeliveryException.NO_ANSWER);
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    void aSendThatSucceedsRaisesNothing() {
        delivery.attempt(SmsProvider.SOLAPI, () -> { });
    }

    private SmsDeliveryException attemptFailing(RuntimeException cause) {
        return catchThrowableOfType(SmsDeliveryException.class,
                () -> delivery.attempt(SmsProvider.SOLAPI, () -> {
                    throw cause;
                }));
    }
}
