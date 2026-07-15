package com.example.sso.webauthn.internal.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.authentication.HttpSessionPublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsRepository;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;

import java.util.List;
import java.util.Set;

/**
 * Persistence + operations beans for Spring Security's WebAuthn module — the SINGLE passkey
 * store, used for both PRIMARY passwordless login ({@code http.webAuthn(..)} auto-detects the
 * repositories) and the FIDO2 second factor (which verifies assertions via
 * {@link WebAuthnRelyingPartyOperations}). One registered passkey works for both.
 */
@Configuration
public class WebAuthnPasswordlessConfig {

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcOperations jdbcOperations) {
        return new JdbcUserCredentialRepository(jdbcOperations);
    }

    @Bean
    PublicKeyCredentialUserEntityRepository publicKeyCredentialUserEntityRepository(JdbcOperations jdbcOperations) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbcOperations);
    }

    @Bean
    WebAuthnRelyingPartyOperations webAuthnRelyingPartyOperations(
            PublicKeyCredentialUserEntityRepository userEntities,
            UserCredentialRepository userCredentials,
            @Value("${sso.webauthn.rp-id:localhost}") String rpId,
            @Value("${sso.webauthn.rp-name:Mini SSO}") String rpName,
            @Value("${sso.tenant.base-domains}") List<String> baseDomains,
            @Qualifier("webAuthnAllowedOrigins") Set<String> allowedOrigins) {
        // The RP ID is derived from the ceremony host per request (WebAuthnRpIdResolver): a subdomain of a
        // single-label base like *.localhost must use the full host, since the browser refuses the base itself as
        // its RP ID. allowedOrigins is likewise the TENANT-AWARE set, so the single bean validates passkey
        // ceremonies at every tenant host, not only the platform, for BOTH registration and assertion.
        WebAuthnRpIdResolver rpIds = new WebAuthnRpIdResolver(baseDomains, rpId);
        return new TenantAwareRelyingPartyOperations(rpIds, id -> {
            PublicKeyCredentialRpEntity rp = PublicKeyCredentialRpEntity.builder().id(id).name(rpName).build();
            return new Webauthn4JRelyingPartyOperations(userEntities, userCredentials, rp, allowedOrigins);
        });
    }

    @Bean
    PublicKeyCredentialRequestOptionsRepository publicKeyCredentialRequestOptionsRepository() {
        return new HttpSessionPublicKeyCredentialRequestOptionsRepository();
    }

    /**
     * Registration ceremony store that survives Redis sessions (the default one stores a non-Serializable
     * options object). Wired into {@code http.webAuthn(..)} by {@code SecurityConfig}.
     */
    @Bean
    PublicKeyCredentialCreationOptionsRepository publicKeyCredentialCreationOptionsRepository(
            WebAuthnRelyingPartyOperations operations) {
        return new RegistrationChallengeOptionsRepository(operations);
    }
}
