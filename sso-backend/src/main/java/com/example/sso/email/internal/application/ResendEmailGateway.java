package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;
import com.example.sso.email.template.OutboundEmail;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Resend's HTTPS API.
 *
 * <p>The reason this exists: outbound submission ports are commonly blocked on corporate and hosted networks,
 * and a relay that cannot be connected to is not something an administrator can fix from inside this product.
 * 443 is allowed almost everywhere, so the same mail goes out where SMTP simply times out.
 *
 * <p>Timeout-bounded like every other outbound call here. Mail is sent from a bounded pool, but a provider that
 * never answers would still hold one of its threads for as long as it liked.
 */
@Component
class ResendEmailGateway implements EmailGateway {

    private final RestClient http;
    private final String endpoint;

    ResendEmailGateway(@Value("${sso.email.resend.endpoint}") String endpoint,
            @Value("${sso.email.http-timeout}") Duration timeout) {
        this.endpoint = endpoint;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public EmailProvider provider() {
        return EmailProvider.RESEND;
    }

    @Override
    public void send(MailServer account, OutboundEmail email) {
        http.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + account.apiKey())
                .body(Map.of(
                        "from", account.fromAddress(),
                        "to", email.to(),
                        "subject", email.subject(),
                        "html", email.htmlBody(),
                        "text", email.textBody()))
                .retrieve()
                .toBodilessEntity();
    }
}
