package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Runs a provider call and turns whatever comes back into an {@link SmsDeliveryException} the caller can act
 * on — shared, because both gateways need the same judgement and it is the judgement, not the HTTP, that is
 * easy to get wrong.
 */
@Component
class SmsDelivery {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Runs {@code call}, translating a refusal or a failure to reach the provider.
     *
     * @param sentFrom the sending number as this call actually put it on the wire */
    void attempt(SmsProvider provider, String sentFrom, Runnable call) {
        try {
            call.run();
        } catch (HttpStatusCodeException refused) {
            throw SmsDeliveryException.refused(provider, sentFrom, errorCode(refused),
                    explanation(refused), refused);
        } catch (ResourceAccessException unreachable) {
            throw SmsDeliveryException.unreachable(provider, sentFrom, reachability(unreachable), unreachable);
        }
    }

    /**
     * The provider's own error code, so an operator sees {@code FailedToAddMessage} rather than a status
     * number. Only the CODE is taken: the accompanying message is free text from a third party and would end
     * up verbatim in an audit row and a log line.
     */
    private String errorCode(HttpStatusCodeException refused) {
        try {
            JsonNode body = JSON.readTree(refused.getResponseBodyAsString());
            // Solapi: errorCode. Twilio: code (numeric).
            JsonNode code = body.hasNonNull("errorCode") ? body.get("errorCode") : body.get("code");
            if (code != null && !code.asText().isBlank()) {
                return code.asText();
            }
        } catch (RuntimeException | JsonProcessingException notJson) {
            // A body that is not the documented shape tells us nothing; the status still does.
        }
        return "HTTP " + refused.getStatusCode().value();
    }

    /**
     * The provider's own explanation, for the log line only. {@code FailedToAddMessage} is Solapi's catch-all —
     * the reason that actually helps ("발신번호 미등록", an expired key, no balance) lives here.
     */
    private String explanation(HttpStatusCodeException refused) {
        try {
            JsonNode body = JSON.readTree(refused.getResponseBodyAsString());
            JsonNode message = body.hasNonNull("errorMessage") ? body.get("errorMessage") : body.get("message");
            return message == null ? null : message.asText();
        } catch (RuntimeException | JsonProcessingException notJson) {
            return null;
        }
    }

    /**
     * Whether the message is KNOWN not to have gone out. A refused connection or an unresolvable host proves
     * it; a timeout does not — the request may have been accepted and only the answer lost, and re-sending
     * that bills the tenant twice and texts the person twice.
     */
    private String reachability(ResourceAccessException unreachable) {
        Throwable cause = unreachable.getCause();
        boolean neverOpened = cause instanceof ConnectException || cause instanceof UnknownHostException;
        return neverOpened ? SmsDeliveryException.NOT_CONNECTED : SmsDeliveryException.NO_ANSWER;
    }
}
