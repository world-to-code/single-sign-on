package com.example.sso.federation.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-tenant OIDC-provider registry. Reads list the ACTING tier's own providers and writes go ONLY to that
 * tier via the fail-closed {@link #writableOrg} — a bound-but-orgless non-platform caller can neither see nor
 * edit the global providers. The issuer host is SSRF-validated and the client secret SecretCipher-encrypted
 * BEFORE persist; the plaintext never reaches the DB, a log, or a view. Mirrors {@code SmtpSettingsService}.
 */
@Service
@RequiredArgsConstructor
public class IdentityProviderServiceImpl implements IdentityProviderService {

    private static final Pattern ALIAS = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$");

    private final IdentityProviderRepository repository;
    private final SecretCipher cipher;
    private final FederatedIdentityLinkStore links;
    private final UserService users;
    private final ApplicationEventPublisher events;
    private final OutboundHostValidator hostValidator;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public List<IdentityProviderView> list() {
        return ownProviders().stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityProviderView get(String alias) {
        return ownProvider(alias).map(this::toView)
                .orElseThrow(() -> new NotFoundException("Identity provider not found"));
    }

    @Override
    @Transactional
    public void save(IdentityProviderSpec spec) {
        UUID org = writableOrg();
        String alias = normalizeAlias(spec.alias());
        validate(spec);
        String scopes = normalizeScopes(spec.scopes());
        Optional<IdentityProvider> existing = ownProvider(alias);
        String encrypted = resolveSecret(spec, existing.orElse(null));
        existing.ifPresentOrElse(
                row -> {
                    // Repointing the alias at a DIFFERENT upstream retires the identities the old one minted:
                    // they were proven against that issuer, and a colliding `sub` at the new one must not
                    // inherit the account they resolve to.
                    retireLinksIfUpstreamChanged(org, alias, row, spec);
                    row.reconfigure(spec.displayName().trim(), spec.issuerUri().trim(), spec.clientId().trim(),
                            encrypted, scopes, spec.allowJitProvisioning(), spec.linkByVerifiedEmail(), spec.enabled());
                },
                () -> repository.save(IdentityProvider.create(org, alias, spec.displayName().trim(),
                        spec.issuerUri().trim(), spec.clientId().trim(), encrypted, scopes,
                        spec.allowJitProvisioning(), spec.linkByVerifiedEmail(), spec.enabled())));
    }

    @Override
    @Transactional
    public void delete(String alias) {
        UUID org = writableOrg();
        ownProvider(alias).ifPresent(provider -> { // ownProvider normalizes + validates the alias
            // Links outlive the provider row unless dropped here: a re-created alias pointing at an attacker's
            // issuer would otherwise resolve the retired identities to their old accounts.
            retireLinks(org, provider.getAlias(), provider.getIssuerUri());
            repository.delete(provider);
        });
    }

    /**
     * Retires this provider's identities when the upstream it authenticates against changes. Both halves of
     * the identifier matter: the issuer obviously, and the CLIENT ID because under pairwise subject
     * identifiers (Azure AD app-scoped ids, Apple, anything using a sector identifier) the subject namespace
     * is per-client — rotating the app registration gives every user a new {@code sub}, and links left behind
     * would strand the whole tenant on the login path's fail-closed guard.
     */
    private void retireLinksIfUpstreamChanged(UUID org, String alias, IdentityProvider row,
            IdentityProviderSpec spec) {
        boolean sameUpstream = row.getIssuerUri().equals(spec.issuerUri().trim())
                && row.getClientId().equals(spec.clientId().trim());
        if (!sameUpstream) {
            retireLinks(org, alias, row.getIssuerUri());
        }
    }

    /**
     * Retires an upstream's identities for this tier. A PLATFORM-tier provider ({@code org == null}) owns none:
     * federated_identity.org_id is NOT NULL and login resolves providers strictly per-tenant, so a global
     * provider can never mint a link. Stated explicitly rather than left to a null binding matching no rows —
     * if global providers ever become login-reachable, this is where that decision has to be revisited.
     */
    private void retireLinks(UUID org, String alias, String issuer) {
        if (org == null) {
            return;
        }
        // Two providers may legitimately point at the same upstream. Retiring by issuer alone would wipe the
        // OTHER one's identities too, and every login through it would fall back to bootstrapping by address.
        if (repository.existsByOrgIdAndIssuerUriAndAliasNot(org, issuer, alias)) {
            return;
        }
        // Revoking the credential is only half of it: the sessions it authenticated stay valid until they
        // expire otherwise, which is precisely what an admin repointing a compromised upstream is trying to
        // stop. Terminating goes through the established access-change path (Redis + BCL/SLO propagation).
        for (UUID retired : links.unlinkAll(org, issuer)) {
            users.usernameOf(retired)
                    .ifPresent(username -> events.publishEvent(new UserAccessChangedEvent(username, org)));
        }
    }

    /**
     * The ciphertext to persist. A newly-supplied secret is encrypted; a BLANK secret on an update KEEPS the
     * stored ciphertext — the write-only secret is never echoed back, so an edit of other fields must not wipe
     * it. A brand-new provider MUST carry a secret.
     */
    private String resolveSecret(IdentityProviderSpec spec, IdentityProvider existing) {
        if (StringUtils.hasText(spec.clientSecret())) {
            return cipher.encrypt(spec.clientSecret().trim());
        }
        if (existing != null) {
            return existing.getClientSecretEncrypted();
        }
        throw new BadRequestException("A client secret is required for a new identity provider.");
    }

    private void validate(IdentityProviderSpec spec) {
        if (!StringUtils.hasText(spec.displayName())) {
            throw new BadRequestException("A display name is required.");
        }
        if (!StringUtils.hasText(spec.clientId())) {
            throw new BadRequestException("A client id is required.");
        }
        validateIssuer(spec.issuerUri());
    }

    /** The issuer must be an absolute https URL, and its host must not resolve to an internal/metadata target. */
    private void validateIssuer(String issuerUri) {
        if (!StringUtils.hasText(issuerUri)) {
            throw new BadRequestException("An issuer URL is required.");
        }
        URI uri;
        try {
            uri = new URI(issuerUri.trim());
        } catch (URISyntaxException e) {
            throw new BadRequestException("The issuer URL is malformed.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new BadRequestException("The issuer URL must be an absolute https URL.");
        }
        hostValidator.validate(uri.getHost()); // SSRF: reject internal/metadata targets
    }

    private String normalizeAlias(String alias) {
        String trimmed = alias == null ? "" : alias.trim().toLowerCase();
        if (!ALIAS.matcher(trimmed).matches()) {
            throw new BadRequestException("The alias must be 2–64 lowercase letters, digits or hyphens.");
        }
        return trimmed;
    }

    /** Requested scopes with {@code openid} guaranteed present (OIDC requires it), de-duplicated, space-joined. */
    private String normalizeScopes(String scopes) {
        Set<String> requested = new LinkedHashSet<>();
        requested.add(OidcScopes.OPENID);
        if (StringUtils.hasText(scopes)) {
            Arrays.stream(scopes.trim().split("[,\\s]+"))
                    .filter(StringUtils::hasText)
                    .map(s -> s.toLowerCase())
                    .forEach(requested::add);
        }
        return String.join(" ", requested);
    }

    private IdentityProviderView toView(IdentityProvider p) {
        return new IdentityProviderView(p.getAlias(), p.getDisplayName(), p.getIssuerUri(), p.getClientId(),
                p.getScopes(), p.isAllowJitProvisioning(), p.isLinkByVerifiedEmail(), p.isEnabled());
    }

    /**
     * The acting tier's OWN providers. Symmetric with {@link #writableOrg()}: only the PLATFORM tier owns the
     * global (org_id NULL) providers — a bound-but-orgless non-platform caller owns nothing.
     */
    private List<IdentityProvider> ownProviders() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return repository.findByOrgIdOrderByAlias(org);
        }
        return orgContext.isPlatform() ? repository.findByOrgIdIsNullOrderByAlias() : List.of();
    }

    private Optional<IdentityProvider> ownProvider(String rawAlias) {
        String alias = normalizeAlias(rawAlias); // parity with save/delete: an uppercase/invalid alias resolves the same
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return repository.findByOrgIdAndAlias(org, alias);
        }
        return orgContext.isPlatform() ? repository.findByOrgIdIsNullAndAlias(alias) : Optional.empty();
    }

    /** The acting org for a WRITE. Deny-by-default: a bound-but-orgless non-platform caller can't write global. */
    private UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            throw new ForbiddenException("Only a platform administrator may edit the global identity providers.");
        }
        return org;
    }
}
