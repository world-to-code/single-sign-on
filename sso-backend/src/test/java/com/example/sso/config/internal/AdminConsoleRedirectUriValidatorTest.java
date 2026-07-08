package com.example.sso.config.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security-critical core of {@link AdminConsoleRedirectUriValidator}: the admin-console callback is
 * accepted ONLY when it is EXACTLY the request's own origin (scheme + host:port) plus {@code /admin/callback}.
 * This pins a code to the host the authorize request arrived on, so it can never be redirected to another
 * origin or tenant (no open redirect / cross-tenant code interception) even though tenant subdomains are not
 * pre-registered.
 */
class AdminConsoleRedirectUriValidatorTest {

    private MockHttpServletRequest request(String scheme, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.addHeader("Host", host);
        return request;
    }

    @Test
    void acceptsTheSameOriginAdminCallbackAtATenantSubdomain() {
        MockHttpServletRequest request = request("http", "octatco.localhost:9000");
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "http://octatco.localhost:9000/admin/callback")).isTrue();
    }

    @Test
    void acceptsTheSameOriginAdminCallbackAtThePlatformHost() {
        MockHttpServletRequest request = request("https", "idp.example.com");
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "https://idp.example.com/admin/callback")).isTrue();
    }

    @Test
    void rejectsADifferentHost() {
        // The prime attack: an authorize request at octatco's host asking to redirect the code to another host.
        MockHttpServletRequest request = request("http", "octatco.localhost:9000");
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "http://evil.localhost:9000/admin/callback")).isFalse();
    }

    @Test
    void rejectsADifferentSchemeOrPort() {
        MockHttpServletRequest request = request("http", "octatco.localhost:9000");
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "https://octatco.localhost:9000/admin/callback")).isFalse();
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "http://octatco.localhost:8443/admin/callback")).isFalse();
    }

    @Test
    void rejectsADifferentPath() {
        MockHttpServletRequest request = request("http", "octatco.localhost:9000");
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "http://octatco.localhost:9000/admin/callback/../evil")).isFalse();
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request, "http://octatco.localhost:9000/")).isFalse();
    }

    @Test
    void rejectsNullRequestRedirectOrMissingHost() {
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                null, "http://x/admin/callback")).isFalse();
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                request("http", "octatco.localhost:9000"), null)).isFalse();
        MockHttpServletRequest noHost = new MockHttpServletRequest();
        noHost.setScheme("http");
        assertThat(AdminConsoleRedirectUriValidator.isSameOriginAdminCallback(
                noHost, "http://octatco.localhost:9000/admin/callback")).isFalse();
    }
}
