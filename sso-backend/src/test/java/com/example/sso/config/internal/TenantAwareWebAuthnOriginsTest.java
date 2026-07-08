package com.example.sso.config.internal;

import com.example.sso.security.HostOrgResolver;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantAwareWebAuthnOrigins} admits the current request's own origin ONLY when its host is a base
 * domain or a live tenant, so a passkey ceremony validates at a tenant subdomain without pre-registration —
 * while a foreign/unknown host is never admitted (the origin check still blocks phishing).
 */
class TenantAwareWebAuthnOriginsTest {

    private static final Set<String> CONFIGURED = Set.of("http://localhost:9000", "http://localhost:5173");

    private final HostOrgResolver hostOrgResolver = mock(HostOrgResolver.class);
    private final TenantAwareWebAuthnOrigins origins = new TenantAwareWebAuthnOrigins(CONFIGURED, hostOrgResolver);

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void onRequest(String scheme, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.addHeader("Host", host);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void withoutARequestContextOnlyTheConfiguredOriginsAreAllowed() {
        assertThat(origins).containsExactlyInAnyOrderElementsOf(CONFIGURED);
    }

    @Test
    void admitsTheCurrentTenantSubdomainOrigin() {
        lenient().when(hostOrgResolver.isBaseDomain("acme.localhost:9000")).thenReturn(false);
        when(hostOrgResolver.resolveOrg("acme.localhost:9000")).thenReturn(Optional.of(UUID.randomUUID()));
        onRequest("http", "acme.localhost:9000");

        assertThat(origins).contains("http://acme.localhost:9000").containsAll(CONFIGURED);
        assertThat(origins.contains("http://acme.localhost:9000")).isTrue();
    }

    @Test
    void admitsThePlatformBaseHostOrigin() {
        when(hostOrgResolver.isBaseDomain("localhost:9000")).thenReturn(true);
        onRequest("http", "localhost:9000");

        assertThat(origins).contains("http://localhost:9000");
    }

    @Test
    void neverAdmitsAnUnknownOrForeignHost() {
        when(hostOrgResolver.isBaseDomain("evil.example.com")).thenReturn(false);
        when(hostOrgResolver.resolveOrg("evil.example.com")).thenReturn(Optional.empty());
        onRequest("https", "evil.example.com");

        assertThat(origins).containsExactlyInAnyOrderElementsOf(CONFIGURED)
                .doesNotContain("https://evil.example.com");
    }
}
