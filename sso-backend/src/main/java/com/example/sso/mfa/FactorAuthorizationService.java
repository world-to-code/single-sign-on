package com.example.sso.mfa;

import com.example.sso.authpolicy.Factors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Establishes and upgrades the session {@link Authentication} for the custom JSON auth flow,
 * adding completed factor authorities so they survive subsequent requests (Spring Security 7's
 * factor-aware model). Because this flow does not go through the standard authentication
 * filters, it applies session-fixation protection itself on the initial login.
 */
@Service
public class FactorAuthorizationService {

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SessionAuthenticationStrategy sessionStrategy = new ChangeSessionIdAuthenticationStrategy();

    /**
     * Establishes a brand-new authenticated context (e.g. after password login) in the session,
     * rotating the session id first to defend against session-fixation (the filter-based
     * {@code SessionAuthenticationStrategy} does not run on this custom login path).
     */
    public void establish(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    public boolean grantFactor(HttpServletRequest request, HttpServletResponse response, String factorAuthority) {
        Authentication current = contextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated()) {
            return false;
        }
        Authentication upgraded = current.toBuilder()
                .authorities(authorities -> {
                    boolean alreadyPresent = authorities.stream()
                            .anyMatch(a -> factorAuthority.equals(a.getAuthority()));
                    if (!alreadyPresent) {
                        authorities.add(new SimpleGrantedAuthority(factorAuthority));
                    }
                })
                .build();

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(upgraded);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return true;
    }

    /**
     * Marks a successful DELIBERATE step-up re-auth on the session {@link Authentication}: refreshes the
     * login {@code AUTH_TIME_*} marker AND stamps a {@code STEPUP_TIME_*} marker for the current epoch
     * second. An OIDC token minted afterwards carries a fresh {@code stepup_time} (and {@code auth_time}),
     * which the admin elevation gate requires — proving a recent re-authentication, not just a recent
     * login (RFC 9470).
     */
    public boolean restampAuthTime(HttpServletRequest request, HttpServletResponse response) {
        Authentication current = contextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated()) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        Authentication restamped = current.toBuilder()
                .authorities(authorities -> {
                    authorities.removeIf(a -> a.getAuthority().startsWith(Factors.AUTH_TIME_PREFIX)
                            || a.getAuthority().startsWith(Factors.STEPUP_TIME_PREFIX));
                    authorities.add(new SimpleGrantedAuthority(Factors.AUTH_TIME_PREFIX + now));
                    authorities.add(new SimpleGrantedAuthority(Factors.STEPUP_TIME_PREFIX + now));
                })
                .build();

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(restamped);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return true;
    }
}
