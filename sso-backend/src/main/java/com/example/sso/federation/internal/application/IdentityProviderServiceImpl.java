package com.example.sso.federation.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.federation.FederationProtocol;
import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.federation.OidcConfig;
import com.example.sso.federation.SamlConfig;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.federation.internal.domain.ProviderFlags;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.opensaml.saml.saml2.core.NameIDType;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
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

    /** Qualifies a SAML EntityID before it is used as a link namespace — see {@link #upstreamIssuerOf}. */
    private static final String SAML_LINK_NAMESPACE = "saml:";

    /**
     * The only NameID format a connection may use. PERSISTENT is the one format the SAML spec guarantees is a
     * stable, opaque, per-SP identifier — which is exactly what {@code identity-binding.md} requires of a join
     * key. EMAIL is refused because an address is reassignable (a recycled corporate address would inherit the
     * previous holder's account through an authoritative link, bypassing every safeguard the opt-in
     * {@code linkByVerifiedEmail} path carries); UNSPECIFIED because it promises nothing at all, so the upstream
     * may send either of the above or a transient pseudonym. Widening this needs the same first-binding-only and
     * privileged-account bars that {@code linkByVerifiedEmail} has, not just another entry here.
     */
    private static final Set<String> SUPPORTED_NAME_ID_FORMATS = Set.of(NameIDType.PERSISTENT);

    /** SAML 2.0 bounds an EntityID at 1024 characters; an unbounded value would also overflow a btree key. */
    private static final int MAX_ENTITY_ID_LENGTH = 1024;

    private static final int MAX_CERTIFICATE_LENGTH = 16_384;

    private static final Pattern ALIAS = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$");

    private final IdentityProviderRepository repository;
    private final SecretCipher cipher;
    private final FederatedIdentityLinkStore links;
    private final UserService users;
    private final ApplicationEventPublisher events;
    private final OutboundHostValidator hostValidator;
    private final OrgContext orgContext;
    private final FederationPresetCatalog presets;
    private final FederationSourceSeeder sourceSeeder;
    private final FederationSourceGrantCeiling grantCeiling;

    @Override
    @Transactional(readOnly = true)
    public List<IdentityProviderView> list() {
        return ownProviders().stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public IdentityProviderView get(String alias) {
        return ownProvider(alias).map(this::toView)
                .orElseThrow(() -> NotFoundException.of("federation.provider.notFound"));
    }

    @Override
    @Transactional
    public void save(IdentityProviderSpec spec) {
        UUID org = writableOrg();
        String alias = normalizeAlias(spec.alias());
        validate(spec);
        Optional<IdentityProvider> existing = ownProvider(alias);
        existing.ifPresent(row -> requireSameProtocol(row, spec));
        // Registering (or re-saving) a provider makes the actor an author of that protocol's attribute source,
        // and the mapping evaluator requires EVERY author to be able to assign what the source's values confer.
        // Refuse here rather than let a restricted admin silently freeze the tenant's grants — including on an
        // update, which re-stamps configuredBy and so adds the same author.
        grantCeiling.requireAuthorityOverSource(spec.protocol());
        ProviderFlags flags = new ProviderFlags(spec.allowJitProvisioning(), spec.linkByVerifiedEmail(),
                spec.enabled(), normalizePreset(spec.presetId()));
        switch (spec.config()) {
            case OidcConfig oidc -> saveOidc(org, alias, spec.displayName().trim(), oidc, existing.orElse(null),
                    flags);
            case SamlConfig saml -> saveSaml(org, alias, spec.displayName().trim(), saml, existing.orElse(null),
                    flags);
        }
    }

    @Override
    @Transactional
    public void delete(String alias) {
        UUID org = writableOrg();
        ownProvider(alias).ifPresent(provider -> { // ownProvider normalizes + validates the alias
            // Links outlive the provider row unless dropped here: a re-created alias pointing at an attacker's
            // issuer would otherwise resolve the retired identities to their old accounts.
            retireLinks(org, provider.getAlias(), upstreamIssuerOf(provider));
            repository.delete(provider);
        });
    }

    private void saveOidc(UUID org, String alias, String displayName, OidcConfig config, IdentityProvider existing,
            ProviderFlags flags) {
        String scopes = normalizeScopes(config.scopes());
        String encrypted = resolveSecret(config, existing);
        if (existing == null) {
            IdentityProvider row = IdentityProvider.createOidc(org, alias, displayName,
                    config.issuerUri().trim(), config.clientId().trim(), encrypted, scopes, flags);
            row.configuredBy(resolveConfigurator());
            repository.save(row);
            seedSource(org, FederationProtocol.OIDC);
            return;
        }
        // Repointing the alias at a DIFFERENT upstream retires the identities the old one minted: they were
        // proven against that issuer, and a colliding `sub` at the new one must not inherit the account they
        // resolve to. The CLIENT ID counts as the upstream too — under pairwise subject identifiers (Entra
        // app-scoped ids, Apple, any sector identifier) the subject namespace is per-client, so rotating the
        // app registration gives every user a new `sub` and links left behind would strand the whole tenant.
        boolean sameUpstream = config.issuerUri().trim().equals(existing.getIssuerUri())
                && config.clientId().trim().equals(existing.getClientId());
        if (!sameUpstream) {
            retireLinks(org, alias, existing.getIssuerUri());
        }
        existing.reconfigureOidc(displayName, config.issuerUri().trim(), config.clientId().trim(), encrypted,
                scopes, flags);
        existing.configuredBy(resolveConfigurator());
        seedSource(org, FederationProtocol.OIDC);
    }

    /**
     * A tenant's federated logins fill attributes through ONE connector-less source profile PER PROTOCOL (like
     * SCIM), so an OIDC claim and a SAML attribute of the same name stay distinguishable — provenance is what
     * the attribute-write guard reads. Ensured idempotently on any tenant write; a platform-tier provider
     * (org null) owns none.
     */
    private void seedSource(UUID org, FederationProtocol protocol) {
        if (org != null) {
            sourceSeeder.ensureSource(org, protocol);
        }
    }

    private void saveSaml(UUID org, String alias, String displayName, SamlConfig config, IdentityProvider existing,
            ProviderFlags flags) {
        String entityId = config.idpEntityId().trim();
        requireUnclaimedUpstream(alias, entityId);
        if (existing == null) {
            IdentityProvider row = IdentityProvider.createSaml(org, alias, displayName, entityId,
                    config.ssoUrl().trim(), config.signingCertificate().trim(),
                    requireStableNameIdFormat(config.nameIdFormat()), flags);
            row.configuredBy(resolveConfigurator());
            repository.save(row);
            seedSource(org, FederationProtocol.SAML);
            return;
        }
        // The SAML twin of the OIDC rule above, with one addition that has no OIDC analogue: the SIGNING
        // CERTIFICATE is the trust anchor, and unlike OIDC (whose keys come from the issuer's own JWKS) an
        // administrator supplies it. Replacing it repoints who may speak for this upstream just as surely as
        // changing the EntityID does — leaving the links in place would let a new key assert the old identities.
        // Rotation therefore costs a re-link; overlapping certificates would need a multi-certificate column.
        boolean sameUpstream = entityId.equals(existing.getIdpEntityId())
                && config.signingCertificate().trim().equals(existing.getSigningCertificate());
        if (!sameUpstream) {
            retireLinks(org, alias, upstreamIssuerOf(existing));
        }
        existing.reconfigureSaml(displayName, entityId, config.ssoUrl().trim(),
                config.signingCertificate().trim(), requireStableNameIdFormat(config.nameIdFormat()), flags);
        existing.configuredBy(resolveConfigurator());
        seedSource(org, FederationProtocol.SAML);
    }

    /**
     * Refuses a second alias pointing at an upstream this tier already federates to. The partial unique index is
     * what actually holds the invariant — this check has no decision under two concurrent writes — but without it
     * the admin sees the generic "a concurrent write lost the race" 409 for what is really a duplicate.
     */
    private void requireUnclaimedUpstream(String alias, String entityId) {
        boolean claimedByAnother = ownProviders().stream()
                .filter(p -> p.getProtocol() == FederationProtocol.SAML)
                .anyMatch(p -> entityId.equals(p.getIdpEntityId()) && !alias.equals(p.getAlias()));
        if (claimedByAnother) {
            throw BadRequestException.of("federation.provider.entityIdAlreadyRegistered");
        }
    }

    /**
     * An alias may not change protocol. The alias is the login route and the link retirement key, so switching
     * it would silently repoint a live connection at a different upstream shape — and the identities minted
     * under the old protocol would be retired against an identifier the new config no longer carries.
     */
    private void requireSameProtocol(IdentityProvider existing, IdentityProviderSpec spec) {
        if (existing.getProtocol() != spec.protocol()) {
            throw BadRequestException.of("federation.provider.protocolImmutable");
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
        // Revoking the credential is only half of it: the sessions it authenticated stay valid until they
        // expire otherwise, which is precisely what an admin repointing a compromised upstream is trying to
        // stop. Terminating goes through the established access-change path (Redis + BCL/SLO propagation).
        List<UUID> retired = links.unlinkAll(org, issuer, alias);
        // One lookup for the whole set: a tenant that migrated its directory to this upstream can have
        // thousands of identities here, and a per-row query would run them all inside this admin write.
        users.usernamesOf(retired)
                .forEach(username -> events.publishEvent(new UserAccessChangedEvent(username, org)));
    }

    /**
     * The ciphertext to persist. A newly-supplied secret is encrypted; a BLANK secret on an update KEEPS the
     * stored ciphertext — the write-only secret is never echoed back, so an edit of other fields must not wipe
     * it. A brand-new provider MUST carry a secret.
     */
    private String resolveSecret(OidcConfig config, IdentityProvider existing) {
        if (StringUtils.hasText(config.clientSecret())) {
            return cipher.encrypt(config.clientSecret().trim());
        }
        if (existing != null) {
            return existing.getClientSecretEncrypted();
        }
        throw BadRequestException.of("federation.provider.clientSecretRequired");
    }

    /**
     * The namespace this provider's federated identities are keyed in.
     *
     * <p>SAML EntityIDs are QUALIFIED, and that qualifier is load-bearing. An OIDC issuer is self-authenticating
     * — the server fetches the JWKS from that very host, so an admin cannot claim someone else's issuer — while a
     * SAML EntityID is a free-form string whose trust anchor is a certificate the same admin supplies. Sharing one
     * namespace would therefore let an actor holding only {@code identity-provider:write} register a SAML provider
     * whose EntityID equals a live OIDC issuer, sign an assertion with their own key, and have a forged NameID
     * resolve that connection's EXISTING links — which are authoritative and skip the privileged-account bar.
     * OIDC keeps the bare issuer: its links predate the qualifier, and its namespace is already self-authenticating.
     */
    private String upstreamIssuerOf(IdentityProvider provider) {
        return provider.getProtocol() == FederationProtocol.SAML
                ? SAML_LINK_NAMESPACE + provider.getIdpEntityId() : provider.getIssuerUri();
    }

    private void validate(IdentityProviderSpec spec) {
        if (!StringUtils.hasText(spec.displayName())) {
            throw BadRequestException.of("federation.provider.displayNameRequired");
        }
        switch (spec.config()) {
            case OidcConfig oidc -> validateOidc(oidc);
            case SamlConfig saml -> validateSaml(saml);
        }
    }

    private void validateOidc(OidcConfig config) {
        if (!StringUtils.hasText(config.clientId())) {
            throw BadRequestException.of("federation.provider.clientIdRequired");
        }
        // The issuer IS fetched by this server (discovery, JWKS), so its host is SSRF-validated.
        String host = requireAbsoluteHttps(config.issuerUri(), "federation.provider.issuerRequired",
                "federation.provider.issuerMalformed", "federation.provider.issuerNotHttps");
        hostValidator.validate(host);
    }

    private void validateSaml(SamlConfig config) {
        if (!StringUtils.hasText(config.idpEntityId())) {
            throw BadRequestException.of("federation.provider.entityIdRequired");
        }
        if (config.idpEntityId().trim().length() > MAX_ENTITY_ID_LENGTH) {
            throw BadRequestException.of("federation.provider.entityIdTooLong");
        }
        // The SSO URL is dereferenced by the BROWSER, never by this server, so OutboundHostValidator does not
        // apply: running it here would buy nothing and would reject a perfectly reachable on-prem IdP behind
        // split-horizon DNS. Any future SERVER-side fetch (SAML metadata retrieval) must validate at fetch time.
        requireAbsoluteHttps(config.ssoUrl(), "federation.provider.ssoUrlRequired",
                "federation.provider.ssoUrlMalformed", "federation.provider.ssoUrlNotHttps");
        requireParsableCertificate(config.signingCertificate());
        requireStableNameIdFormat(config.nameIdFormat());
    }

    /** An upstream endpoint must be an absolute https URL. Returns its host so a caller can validate further. */
    private String requireAbsoluteHttps(String value, String requiredKey, String malformedKey, String notHttpsKey) {
        if (!StringUtils.hasText(value)) {
            throw BadRequestException.of(requiredKey);
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw BadRequestException.of(malformedKey);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw BadRequestException.of(notHttpsKey);
        }
        return uri.getHost();
    }

    /**
     * The pinned signing certificate must actually parse — a provider whose certificate is unusable would fail
     * open at the ACS if the verifier ever treated "no usable key" as "nothing to check". Refuse at the write.
     */
    private void requireParsableCertificate(String pem) {
        if (!StringUtils.hasText(pem)) {
            throw BadRequestException.of("federation.provider.certificateRequired");
        }
        if (pem.trim().length() > MAX_CERTIFICATE_LENGTH) {
            throw BadRequestException.of("federation.provider.certificateTooLong");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            factory.generateCertificate(new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (CertificateException malformed) {
            throw BadRequestException.of("federation.provider.certificateMalformed");
        }
    }

    /**
     * The NameID format, which is REQUIRED. Leaving it unset would not mean "no opinion" — it would mean the
     * upstream picks, and a transient pseudonym resolves to no link on every sign-in, JIT-provisioning a
     * duplicate account each time. The guard has to sit on the default, not only on the value an admin types.
     */
    private String requireStableNameIdFormat(String nameIdFormat) {
        if (!StringUtils.hasText(nameIdFormat)) {
            throw BadRequestException.of("federation.provider.nameIdFormatRequired");
        }
        String trimmed = nameIdFormat.trim();
        if (!SUPPORTED_NAME_ID_FORMATS.contains(trimmed)) {
            throw BadRequestException.of("federation.provider.nameIdFormatUnsupported");
        }
        return trimmed;
    }

    /**
     * The preset (vendor) tag to store: {@code null} for a custom provider, otherwise a preset id the catalog
     * knows. Validated against the catalog so a client cannot plant an arbitrary vendor label — the badge it
     * drives is only display, but an unbounded string is still input we do not need to keep.
     */
    private String normalizePreset(String presetId) {
        if (!StringUtils.hasText(presetId)) {
            return null;
        }
        String trimmed = presetId.trim();
        boolean known = presets.list().stream().anyMatch(preset -> preset.id().equals(trimmed));
        if (!known) {
            throw BadRequestException.of("federation.provider.presetUnknown", trimmed);
        }
        return trimmed;
    }

    /**
     * The administrator behind this request, resolved the way a mapping rule resolves its author: a platform
     * super-admin is a global account, anyone else is looked up in their own tier. Null when there is no
     * authenticated principal (a seeder or a test), which the provenance guard reads as unattributed — never
     * as "nobody", so it fails closed rather than vouching for an unknown author. Mirrors
     * {@code DirectoryConnectorServiceImpl.resolveConfigurator}.
     */
    private UUID resolveConfigurator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        boolean platformAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        return (platformAdmin
                ? users.findByUsernameInOrg(authentication.getName(), null)
                : users.findByUsername(authentication.getName()))
                .map(UserAccount::getId).orElse(null);
    }

    private String normalizeAlias(String alias) {
        String trimmed = alias == null ? "" : alias.trim().toLowerCase();
        if (!ALIAS.matcher(trimmed).matches()) {
            throw BadRequestException.of("federation.provider.aliasInvalid");
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
        return new IdentityProviderView(p.getAlias(), p.getDisplayName(), p.getProtocol(), p.getIssuerUri(),
                p.getClientId(), p.getScopes(), p.getIdpEntityId(), p.getSsoUrl(), p.getSigningCertificate(),
                p.getNameIdFormat(), p.isAllowJitProvisioning(), p.isLinkByVerifiedEmail(), p.isEnabled(),
                p.getPresetId());
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
            throw ForbiddenException.of("federation.provider.global.platformOnly");
        }
        return org;
    }
}
