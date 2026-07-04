package com.example.sso.saml.internal.application;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Binds a front-channel SLO redirect chain to the browser that started it. The {@code logoutId} also
 * travels as RelayState to every participant SP, so it cannot be the sole capability that drives the chain.
 * This HttpOnly cookie — never disclosed to SPs — must match the id before {@code /chain} emits a hop, so a
 * participant SP that only learned the RelayState cannot drain another user's chain from its own server.
 * {@code /chain} is only ever reached same-site (the SPA's post-logout navigation, or the IdP's own
 * advance redirect), so a {@code SameSite=Lax} cookie is always present for the legitimate browser.
 */
@Component
public class SamlLogoutChainCookie {

    static final String NAME = "SLO_CHAIN";
    private static final String PATH = "/saml2/idp/slo/chain";

    private final Duration ttl;
    private final boolean secure;

    public SamlLogoutChainCookie(@Value("${sso.saml.slo.chain-ttl:PT5M}") Duration ttl,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        this.ttl = ttl;
        this.secure = secure;
    }

    public void issue(HttpServletResponse response, String logoutId) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(logoutId, ttl).toString());
    }

    public boolean matches(HttpServletRequest request, String logoutId) {
        return valueOf(request).filter(logoutId::equals).isPresent();
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }

    private Optional<String> valueOf(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
