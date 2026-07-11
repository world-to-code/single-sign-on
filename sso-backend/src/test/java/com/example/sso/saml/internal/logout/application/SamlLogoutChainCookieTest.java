package com.example.sso.saml.internal.logout.application;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chain-binding cookie is what stops a participant SP (which learns the chain id as RelayState) from
 * driving another user's front-channel logout: {@code /chain} only proceeds when the browser presents a
 * cookie matching the id. These assert the issue/match/clear contract the controller relies on.
 */
class SamlLogoutChainCookieTest {

    private final SamlLogoutChainCookie cookie = new SamlLogoutChainCookie(Duration.ofMinutes(5), true);

    @Test
    void issuesAHardenedCookieCarryingTheLogoutId() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.issue(response, "abc-123");

        String header = response.getHeader("Set-Cookie");
        assertThat(header).contains(SamlLogoutChainCookie.NAME + "=abc-123")
                .contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
                .contains("Path=/saml2/idp/slo/chain");
    }

    @Test
    void matchesOnlyWhenTheCookieEqualsTheId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SamlLogoutChainCookie.NAME, "abc-123"));

        assertThat(cookie.matches(request, "abc-123")).isTrue();
        assertThat(cookie.matches(request, "other-id")).isFalse();
    }

    @Test
    void doesNotMatchWhenTheCookieIsAbsent() {
        assertThat(cookie.matches(new MockHttpServletRequest(), "abc-123")).isFalse();
    }

    @Test
    void clearExpiresTheCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.clear(response);

        assertThat(response.getHeader("Set-Cookie")).contains("Max-Age=0");
    }
}
