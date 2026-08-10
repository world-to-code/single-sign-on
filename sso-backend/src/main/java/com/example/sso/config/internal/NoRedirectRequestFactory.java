package com.example.sso.config.internal;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * An HTTP request factory that does not follow redirects.
 *
 * <p>Its own type rather than an anonymous subclass inside a {@code @Bean}: refusing a redirect is a security
 * control, and one buried in a lambda is one nobody finds when they ask what protects this call.
 *
 * <p>What it protects: a 302 re-aims the destination AFTER the URL was validated. For the audit export that
 * means every tenant's security history, plus the collector credential in the header, delivered to a host
 * that never passed the https and SSRF checks.
 */
class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
