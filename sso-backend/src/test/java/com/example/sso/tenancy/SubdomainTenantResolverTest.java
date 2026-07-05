package com.example.sso.tenancy;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SubdomainTenantResolver}: pure Host-header parsing. A single tenant label under a
 * configured base domain resolves; a bare base domain, a nested/multi-label subdomain, an unknown domain,
 * and a missing Host all resolve to no tenant (fail-closed — the caller then treats it as the platform host).
 */
class SubdomainTenantResolverTest {

    private final SubdomainTenantResolver resolver =
            new SubdomainTenantResolver(List.of("localhost", "idp.example.com"));

    @Test
    void extractsTheTenantLabelUnderABaseDomain() {
        assertThat(resolver.tenantSlug("acme.localhost:9000")).contains("acme");
        assertThat(resolver.tenantSlug("acme.localhost")).contains("acme");
        assertThat(resolver.tenantSlug("globex.idp.example.com")).contains("globex");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(resolver.tenantSlug("ACME.LocalHost:9000")).contains("acme");
    }

    @Test
    void aBareBaseDomainHasNoTenant() {
        assertThat(resolver.tenantSlug("localhost:9000")).isEmpty();
        assertThat(resolver.tenantSlug("localhost")).isEmpty();
        assertThat(resolver.tenantSlug("idp.example.com")).isEmpty();
    }

    @Test
    void aNestedOrMultiLabelSubdomainIsNotAcceptedAsATenant() {
        assertThat(resolver.tenantSlug("a.b.localhost")).isEmpty();
        assertThat(resolver.tenantSlug("x.y.idp.example.com")).isEmpty();
    }

    @Test
    void anUnknownDomainHasNoTenant() {
        assertThat(resolver.tenantSlug("acme.evil.com")).isEmpty();
        assertThat(resolver.tenantSlug("example.org")).isEmpty();
    }

    @Test
    void aMissingHostHasNoTenant() {
        assertThat(resolver.tenantSlug(null)).isEmpty();
        assertThat(resolver.tenantSlug("")).isEmpty();
        assertThat(resolver.tenantSlug("   ")).isEmpty();
    }

    @Test
    void anIpv6LiteralHostHasNoTenant() {
        assertThat(resolver.tenantSlug("[::1]:9000")).isEmpty();
    }
}
