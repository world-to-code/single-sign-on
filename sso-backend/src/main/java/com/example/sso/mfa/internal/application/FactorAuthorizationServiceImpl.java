package com.example.sso.mfa.internal.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.mfa.FactorAuthorizationService;
import com.example.sso.webauthn.PasskeyAssurance;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
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
import java.util.Collection;
import java.util.List;

/**
 * Default {@link FactorAuthorizationService}. Establishes and upgrades the session
 * {@link Authentication} for the custom JSON auth flow, adding completed factor authorities so they
 * survive subsequent requests. Because this flow does not go through the standard authentication
 * filters, it applies session-fixation protection itself on the initial login.
 */
@Service
public class FactorAuthorizationServiceImpl implements FactorAuthorizationService {

    private final PasskeyAssurance passkeyAssurance;

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SessionAuthenticationStrategy sessionStrategy = new ChangeSessionIdAuthenticationStrategy();

    public FactorAuthorizationServiceImpl(PasskeyAssurance passkeyAssurance) {
        this.passkeyAssurance = passkeyAssurance;
    }

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

        // A passkey comes in two kinds and the id_token has to say which, so the marker is attached where the
        // factor is — both ways of using a passkey (passwordless login, FIDO2 step-up) pass through here, and a
        // rule copied into each caller is one a later third caller will not know about.
        List<String> granted = Factors.FIDO2.equals(factorAuthority)
                        && passkeyAssurance.lastAssertionWasSoftwareBacked(request)
                ? List.of(factorAuthority, Factors.SOFTWARE_BACKED_PASSKEY)
                : List.of(factorAuthority);

        Authentication upgraded = current.toBuilder()
                .authorities(authorities -> granted.forEach(authority -> addIfAbsent(authorities, authority)))
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
                    // Also refresh the FactorGrantedAuthority so Spring AS's OIDC `auth_time` (derived from
                    // its issuedAt) is fresh — an admin elevation token minted right after this step-up
                    // needs it, and its absence 500s the token endpoint. Reuse an existing factor label.
                    String factor = authorities.stream().map(GrantedAuthority::getAuthority)
                            .filter(AuthFactor::isKnownAuthority).findFirst().orElse(Factors.PASSWORD);
                    authorities.removeIf(FactorGrantedAuthority.class::isInstance);
                    authorities.add(FactorGrantedAuthority.withAuthority(factor).issuedAt(Instant.ofEpochSecond(now)).build());
                })
                .build();

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(restamped);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return true;
    }

    private void addIfAbsent(Collection<GrantedAuthority> authorities, String authority) {
        boolean alreadyPresent = authorities.stream().anyMatch(a -> authority.equals(a.getAuthority()));
        if (!alreadyPresent) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
    }
}
