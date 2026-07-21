package com.example.sso.federation.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.federation.FederationProvider;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.shared.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional read side of the login flow: resolves a tenant's ENABLED provider (with the secret
 * decrypted for internal use) or lists them for the login screen. Its {@code @Transactional} methods are
 * invoked by {@code FederationLoginServiceImpl} INSIDE {@code orgContext.callInOrg(orgId, …)} so the RLS GUC
 * reaches the held connection — the federation login flow runs pre-authentication, when no OrgContext is bound
 * by the request filter. Kept separate from the OIDC orchestration so no DB connection is held across the
 * network round-trips to the upstream.
 */
@Component
@RequiredArgsConstructor
class FederationConfigStore {

    private final IdentityProviderRepository repository;
    private final SecretCipher cipher;

    @Transactional(readOnly = true)
    ResolvedProvider resolveEnabled(UUID orgId, String alias) {
        IdentityProvider p = repository.findByOrgIdAndAlias(orgId, normalize(alias))
                .filter(IdentityProvider::isEnabled)
                .orElseThrow(() -> NotFoundException.of("federation.provider.unknown"));
        return new ResolvedProvider(p.getAlias(), p.getIssuerUri(), p.getClientId(),
                cipher.decrypt(p.getClientSecretEncrypted()), p.getScopes(), p.isAllowJitProvisioning(),
                p.isLinkByVerifiedEmail());
    }

    @Transactional(readOnly = true)
    List<FederationProvider> enabled(UUID orgId) {
        return repository.findByOrgIdOrderByAlias(orgId).stream()
                .filter(IdentityProvider::isEnabled)
                .map(p -> new FederationProvider(p.getAlias(), p.getDisplayName()))
                .toList();
    }

    private String normalize(String alias) {
        return alias == null ? "" : alias.trim().toLowerCase();
    }
}
