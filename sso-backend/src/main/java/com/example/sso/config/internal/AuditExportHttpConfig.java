package com.example.sso.config.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client the audit export sends through, and the two things it must not do.
 *
 * <p><b>Redirects are off</b>, at the client rather than per-connection. A 302 re-aims the destination AFTER
 * the URL was validated, which would hand every tenant's audit trail — and the credential in the header — to a
 * host that never passed the https and SSRF checks.
 *
 * <p><b>It cannot hang.</b> This is the reason for the JDK client over the {@code HttpURLConnection} one: that
 * factory's read timeout is a per-socket-read {@code SO_TIMEOUT}, so a collector dribbling a byte every few
 * seconds holds the thread indefinitely, and its write side has no timeout at all — a collector that completes
 * the handshake and then stops reading blocks while the body is still going out. The JDK client's timeout is a
 * deadline for the WHOLE exchange, which is the only bound that actually terminates.
 *
 * <p>That mattered beyond this feature. Scheduled tasks share one pool, so a collector holding the thread also
 * stopped the audit chain sealer — whose interval is the window in which a deleted row leaves no trace — and
 * the sweepers that expire lapsed privilege. A misbehaving log destination could switch off tamper-evidence
 * and privilege expiry, which is a strange amount of power to hand the far end of a log shipper.
 *
 * <p>The scheme and host are enforced where the target is resolved rather than here, because they must be
 * re-checked at USE against the stored row, not once at client construction.
 */
@Configuration
public class AuditExportHttpConfig {

    @Bean
    RestClient auditExportRestClient(
            @Value("${sso.audit.export.connect-timeout}") Duration connectTimeout,
            @Value("${sso.audit.export.request-timeout}") Duration requestTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(requestTimeout);   // a deadline for the exchange, not for one read
        return RestClient.builder().requestFactory(factory).build();
    }
}
