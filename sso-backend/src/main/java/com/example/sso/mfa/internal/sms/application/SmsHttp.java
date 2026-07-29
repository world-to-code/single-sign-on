package com.example.sso.mfa.internal.sms.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The timeout-bounded HTTP client the gateways send through.
 *
 * <p>Shared so every provider gets the same bound. An unbounded call here would hold a request thread on the
 * login path — the code is being sent DURING a sign-in — so a slow provider would become a slow IdP.
 */
@Component
class SmsHttp {

    private final Duration timeout;

    SmsHttp(@Value("${sso.sms.http-timeout}") Duration timeout) {
        this.timeout = timeout;
    }

    RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
