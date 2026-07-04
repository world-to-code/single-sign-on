package com.example.sso.session.internal.application;

import com.example.sso.session.SessionPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Serializes Spring Session's {@code SESSION} cookie with the IdP's hardening: HttpOnly, {@code Secure}
 * (from {@code server.servlet.session.cookie.secure}), and a {@code SameSite} taken from the Default
 * session policy at write time — cookie attributes are global (the cookie is issued before the user, and
 * hence their per-user policy, is known), so only the Default policy applies, but it stays runtime-editable.
 */
public class PolicyAwareCookieSerializer implements CookieSerializer {

    private final DefaultCookieSerializer delegate = new DefaultCookieSerializer();
    private final SessionPolicyService policyService;

    public PolicyAwareCookieSerializer(SessionPolicyService policyService, boolean secure) {
        this.policyService = policyService;
        this.delegate.setUseHttpOnlyCookie(true);
        this.delegate.setUseSecureCookie(secure);
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        // The Default policy's SameSite. Writes are infrequent (session create/rotate) and set the same
        // global value, so setting it per-write on the shared delegate is safe.
        delegate.setSameSite(policyService.defaultPolicy().getCookieSameSite());
        delegate.writeCookieValue(cookieValue);
    }

    @Override
    public List<String> readCookieValues(HttpServletRequest request) {
        return delegate.readCookieValues(request);
    }
}
