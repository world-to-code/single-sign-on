package com.example.sso.auth.internal.login.application;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Binds an in-flight SAML login to the BROWSER that started it.
 *
 * <p>RelayState alone proves only that <em>somebody</em> started a login. An attacker can start one in their own
 * browser and harvest the {@code SAMLResponse} + {@code RelayState} straight out of the upstream's auto-submit
 * form — it is a form in their own page — then host an auto-submitting copy. A signed-in victim who loads it
 * POSTs a perfectly valid assertion to the ACS, and because that POST is cross-site the session cookie is not
 * sent: {@code CompletedSessionGuard} sees an anonymous context and does not fire, so the victim's session is
 * silently DISPLACED rather than refused — never invalidated, its back-channel-logout participants left
 * registered, and its id no longer in the victim's browser to log out with.
 *
 * <p>This cookie is the missing binding. It is deliberately {@code SameSite=None} — the only value a browser
 * sends on the cross-site ACS POST — which is safe precisely because it is not a session: it carries an opaque
 * single-login handle, is scoped to the federation path, and expires with the login. The session cookie itself
 * stays {@code Lax}.
 */
@Component
public class SamlLoginBrowserCookie {

    static final String NAME = "SAML_SP_LOGIN";
    private static final String PATH = "/api/auth/federation";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Duration ttl;
    private final boolean secure;

    public SamlLoginBrowserCookie(@Value("${sso.saml.inbound.request-ttl}") Duration ttl,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        this.ttl = ttl;
        this.secure = secure;
    }

    /** A fresh 256-bit handle for one login. */
    public String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void issue(HttpServletResponse response, String handle) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(handle, ttl).toString());
    }

    /** Constant-time comparison: the handle is a secret the ACS accepts, so it must not leak by timing. */
    public boolean matches(HttpServletRequest request, String expected) {
        return valueOf(request)
                .filter(present -> MessageDigest.isEqual(present.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8)))
                .isPresent();
    }

    /** Single use: the login it bound is over, whether it succeeded or not. */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    private ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("None") // the ACS POST is cross-site; Lax would not send it at all
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }

    private Optional<String> valueOf(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
