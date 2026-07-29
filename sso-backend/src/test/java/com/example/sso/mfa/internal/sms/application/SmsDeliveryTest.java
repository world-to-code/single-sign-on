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
     * The provider's explanation is kept, separately, because the CODE alone is not diagnosable: Solapi answers
     * {@code FailedToAddMessage} for an unregistered sending number, an expired key and an empty balance alike.
     * Dropping it cost a diagnosis the first time this fired in anger.
     */
    @Test
    void theProvidersExplanationIsKeptForTheLog() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", """
                        {"errorCode":"FailedToAddMessage","errorMessage":"발신번호 미등록"}""".getBytes(), null));

        assertThat(failure.providerDetail()).isEqualTo("발신번호 미등록");
    }

    /**
     * But it stays OUT of the exception message, which is what the audit row and any surfaced error are built
     * from: it is unbounded text from a third party.
     */
    @Test
    void theProvidersExplanationStaysOutOfTheExceptionMessage() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", """
                        {"errorCode":"FailedToAddMessage","errorMessage":"발신번호 미등록"}""".getBytes(), null));

        assertThat(failure.getMessage()).doesNotContain("발신번호").contains("FailedToAddMessage");
    }

    /** And it is bounded, since it is pasted verbatim into a log line. */
    @Test
    void anOverlongExplanationIsTruncated() {
        String flood = "x".repeat(5000);
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", ("{\"errorCode\":\"E\",\"errorMessage\":\"" + flood + "\"}").getBytes(), null));

        assertThat(failure.providerDetail()).hasSizeLessThan(250);
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
        delivery.attempt(SmsProvider.SOLAPI, "010-9999-8888", () -> { });
    }


    /**
     * The number AS SENT travels with the failure. It is the fact in question when a provider says it does not
     * recognise the number, and the log used to print the STORED value instead — identical for a provider we
     * send verbatim to, different for one we normalise for, and indistinguishable in either case.
     */
    @Test
    void theFailureCarriesTheNumberAsItWentOnTheWire() {
        SmsDeliveryException failure = attemptFailing(new HttpClientErrorException(HttpStatus.BAD_REQUEST,
                "Bad Request", """
                        {"errorCode":"FailedToAddMessage","errorMessage":"발신번호 미등록"}""".getBytes(), null));

        assertThat(failure.sentFrom()).isEqualTo("010-9999-8888");
    }

    private SmsDeliveryException attemptFailing(RuntimeException cause) {
        return catchThrowableOfType(SmsDeliveryException.class,
                () -> delivery.attempt(SmsProvider.SOLAPI, "010-9999-8888", () -> {
                    throw cause;
                }));
    }
}
