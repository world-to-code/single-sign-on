package com.example.sso.config.internal;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The client the audit export sends through, and the three things it must not do.
 *
 * <p><b>Redirects are off.</b> A 302 re-aims the destination AFTER the URL was validated, which would let a
 * compromised or repointed collector hand every tenant's audit trail — and the credential in the header — to
 * somewhere that never passed the https and SSRF checks.
 *
 * <p><b>Timeouts are bounded.</b> This runs on a scheduler thread; an unbounded read would let one hanging
 * collector stop the export permanently and silently, which is the state an attacker wants.
 *
 * <p>The scheme and host are enforced where the target is resolved rather than here, because they must be
 * re-checked at USE against the stored row, not once at client construction.
 */
@Configuration
public class AuditExportHttpConfig {

    @Bean
    RestClient auditExportRestClient(
            @Value("${sso.audit.export.connect-timeout}") Duration connectTimeout,
            @Value("${sso.audit.export.read-timeout}") Duration readTimeout) {
        NoRedirectRequestFactory factory = new NoRedirectRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
