package com.example.sso.session.internal.application;

import com.example.sso.audit.AuditService;
import com.example.sso.audit.ServerErrorAuditFilter;
import com.example.sso.session.SessionPolicyService;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.Locale;

/**
 * Session infrastructure beans: the dynamic session-cookie SameSite supplier (so policy changes take
 * effect without a restart; the Secure attribute is driven by {@code server.servlet.session.cookie.secure})
 * and the outermost server-error audit filter. IP access is enforced per-policy, post-authentication, in
 * {@code SessionIntegrityFilter}.
 */
@Configuration
public class SessionCookieConfig {

    @Bean
    public CookieSameSiteSupplier sessionCookieSameSiteSupplier(SessionPolicyService policyService) {
        return cookie -> {
            if (!"JSESSIONID".equals(cookie.getName())) {
                return null; // leave other cookies untouched
            }
            // Cookie attributes are GLOBAL: the session cookie is issued before the user (and hence
            // their per-user policy) is known, so only the Default policy's cookie settings apply.
            String value = policyService.defaultPolicy().getCookieSameSite();
            return Cookie.SameSite.valueOf(value.toUpperCase(Locale.ROOT));
        };
    }

    /** Outermost filter: turns any unhandled 500 into an admin-audited entry + a clean, reference-carrying response. */
    @Bean
    public FilterRegistrationBean<ServerErrorAuditFilter> serverErrorAuditFilterRegistration(AuditService audit) {
        FilterRegistrationBean<ServerErrorAuditFilter> registration =
                new FilterRegistrationBean<>(new ServerErrorAuditFilter(audit));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE); // wrap everything, including the security chain
        registration.addUrlPatterns("/*");
        return registration;
    }
}
