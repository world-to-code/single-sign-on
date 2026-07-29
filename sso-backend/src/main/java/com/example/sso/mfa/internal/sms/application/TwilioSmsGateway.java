package com.example.sso.mfa.internal.sms.application;

import com.example.sso.mfa.SmsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Twilio, the international gateway.
 *
 * <p>HTTP Basic with the Account SID as the username and the auth token as the password, and a form-encoded
 * body rather than JSON — the two differences from Solapi that are the reason each provider gets its own
 * client instead of a shared one parameterised by a URL.
 */
@Component
class TwilioSmsGateway implements SmsGateway {

    private final RestClient http;
    private final String endpointTemplate;

    TwilioSmsGateway(SmsHttp httpFactory, @Value("${sso.sms.twilio.endpoint}") String endpointTemplate) {
        this.http = httpFactory.client();
        this.endpointTemplate = endpointTemplate;
    }

    @Override
    public SmsProvider provider() {
        return SmsProvider.TWILIO;
    }

    @Override
    public void send(SmsAccount account, String to, String message) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        form.add("From", account.senderNumber());
        form.add("Body", message);
        http.post()
                .uri(endpointTemplate, account.apiKey())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth(account.apiKey(), account.apiSecret()))
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
