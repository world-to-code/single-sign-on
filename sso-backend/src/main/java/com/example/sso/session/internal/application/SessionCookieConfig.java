package com.example.sso.session.internal.application;

import com.example.sso.audit.AuditService;
import com.example.sso.audit.ServerErrorAuditFilter;
import com.example.sso.session.SessionPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.session.web.http.CookieSerializer;

/**
 * Session infrastructure beans: the Spring Session {@code SESSION}-cookie serializer (HttpOnly, Secure,
 * and a policy-driven SameSite — see {@link PolicyAwareCookieSerializer}) and the outermost server-error
 * audit filter. IP access is enforced per-policy, post-authentication, in {@code SessionIntegrityFilter}.
 */
@Configuration
public class SessionCookieConfig {

    /**
     * Hardens Spring Session's cookie. The container's {@code server.servlet.session.cookie.*} no longer
     * applies (JSESSIONID isn't issued), so drive HttpOnly/Secure/SameSite here — reusing the Secure flag
     * from the same property so prod stays HTTPS-only.
     */
    @Bean
    public CookieSerializer cookieSerializer(SessionPolicyService policyService,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        return new PolicyAwareCookieSerializer(policyService, secure);
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
