package com.example.sso.mfa.internal.application;

import com.example.sso.authpolicy.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
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
 * Default {@link FactorAuthorizationService}. Establishes and upgrades the session
 * {@link Authentication} for the custom JSON auth flow, adding completed factor authorities so they
 * survive subsequent requests. Because this flow does not go through the standard authentication
 * filters, it applies session-fixation protection itself on the initial login.
 */
@Service
public class FactorAuthorizationServiceImpl implements FactorAuthorizationService {

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SessionAuthenticationStrategy sessionStrategy = new ChangeSessionIdAuthenticationStrategy();

    @Override
    public void establish(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    @Override
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

    @Override
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
