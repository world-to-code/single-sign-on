package com.example.sso.federation.internal.domain;

import com.example.sso.federation.FederationProtocol;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An upstream identity provider a tenant's users can sign in through (Google, Okta, Entra, any OIDC or SAML
 * IdP). A {@code null} {@link #orgId} is a platform-tier provider (the super-admin's own login); a non-null one
 * belongs to that tenant. {@code alias} is the URL-safe handle in the login route
 * ({@code /api/auth/federation/{alias}/start}) and is unique within the tier — which is why both protocols
 * share this table rather than living in siblings that could mint the same alias. {@code clientSecretEncrypted}
 * is SecretCipher ciphertext — the plaintext is never stored, logged, audited, or returned; the login flow
 * decrypts it only to exchange the authorization code.
 */
@Entity
@Table(name = "identity_provider")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdentityProvider extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    /** URL-safe handle, unique within the tier; the {@code {alias}} segment of the login route. */
    @Column(nullable = false, length = 64)
    private String alias;

    /** Human label for the "Sign in with …" button. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /**
     * The wire protocol. The columns below are per-protocol and null for the other one; which set must be
     * present is enforced by the {@code identity_provider_protocol_config} CHECK constraint (V138), not by
     * nullability, so a half-configured provider cannot exist even if a caller bypasses the service.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FederationProtocol protocol;

    /** OIDC: the issuer; discovery ({@code {issuer}/.well-known/openid-configuration}) drives the endpoints. */
    @Column(name = "issuer_uri")
    private String issuerUri;

    /** OIDC: the OAuth client id registered at the upstream. */
    @Column(name = "client_id")
    private String clientId;

    /** OIDC: SecretCipher ciphertext of the OAuth client secret; never the plaintext. The token exchange
     *  decrypts it, so an OIDC provider always carries one (the service enforces it on write). */
    @Column(name = "client_secret_encrypted", columnDefinition = "text")
    private String clientSecretEncrypted;

    /** OIDC: space-separated scopes requested at the upstream; {@code openid} is always required. */
    @Column
    private String scopes;

    /** SAML: the upstream's EntityID. The link namespace derives from it (qualified, so it cannot collide with
     *  an OIDC issuer) — repointing it, or replacing the certificate that vouches for it, retires the identities
     *  the old upstream minted. */
    @Column(name = "idp_entity_id", length = 1024)
    private String idpEntityId;

    /** SAML: the upstream's SSO endpoint, where the AuthnRequest is sent. */
    @Column(name = "sso_url")
    private String ssoUrl;

    /** SAML: the PEM X.509 certificate an assertion MUST verify against — a PINNED key, deliberately not a
     *  trust store resolved from whatever metadata advertises. */
    @Column(name = "signing_certificate", columnDefinition = "text")
    private String signingCertificate;

    /** SAML: the NameID format, required and restricted to {@code persistent} — the only format guaranteed to
     *  be a stable identifier, which is what a link may be keyed on. */
    @Column(name = "name_id_format", length = 128)
    private String nameIdFormat;

    /** Whether a first-time federated user with no local account is provisioned just-in-time (else denied). */
    @Column(name = "allow_jit_provisioning", nullable = false)
    private boolean allowJitProvisioning;

    /**
     * Whether a first login with no link may claim an EXISTING local account by matching its verified email.
     * Off by default: an address can be reassigned upstream, so this is an inference about who somebody is,
     * and it is the one resolution step that can attach a login to an account nobody deliberately connected.
     */
    @Column(name = "link_by_verified_email", nullable = false)
    private boolean linkByVerifiedEmail;

    /** A disabled provider is not offered on the login screen and refuses to start a federated login. */
    @Column(nullable = false)
    private boolean enabled;

    /**
     * The preset (vendor) this provider was created from — a {@code sso.federation.presets} id, or {@code null}
     * for a custom OIDC connection. Display metadata only: the login path never reads it; it lets the console
     * badge the vendor authoritatively instead of guessing from the issuer.
     */
    @Column(name = "preset_id", length = 32)
    private String presetId;

    /**
     * The administrator who last configured this provider, so the attributes its federated logins fill are
     * attributable (the federation twin of {@code DirectoryConnector.configuredBy}). Null = unattributed (a
     * seeder/test, or a row predating the column). Recorded via {@link #configuredBy(UUID)} on every write.
     */
    @Column(name = "configured_by")
    private UUID configuredBy;

    /** An OIDC provider. Owning tenant, or {@code null} for a platform-tier provider. */
    public static IdentityProvider createOidc(UUID orgId, String alias, String displayName, String issuerUri,
            String clientId, String clientSecretEncrypted, String scopes, ProviderFlags flags) {
        IdentityProvider provider = new IdentityProvider();
        provider.orgId = orgId;
        provider.alias = alias;
        provider.reconfigureOidc(displayName, issuerUri, clientId, clientSecretEncrypted, scopes, flags);
        return provider;
    }

    /** A SAML provider. Owning tenant, or {@code null} for a platform-tier provider. */
    public static IdentityProvider createSaml(UUID orgId, String alias, String displayName, String idpEntityId,
            String ssoUrl, String signingCertificate, String nameIdFormat, ProviderFlags flags) {
        IdentityProvider provider = new IdentityProvider();
        provider.orgId = orgId;
        provider.alias = alias;
        provider.reconfigureSaml(displayName, idpEntityId, ssoUrl, signingCertificate, nameIdFormat, flags);
        return provider;
    }

    /** Record the administrator accountable for this provider (intent-revealing, not a JavaBean setter). */
    public void configuredBy(UUID configuredBy) {
        this.configuredBy = configuredBy;
    }

    /**
     * Repoint this OIDC provider; the alias is immutable. The SAML columns are left alone rather than defensively
     * cleared: a provider's protocol never changes (the service refuses it, because switching would repoint a
     * live connection at a different upstream shape), so this row has none — and if that guard were ever lost,
     * the {@code identity_provider_protocol_config} CHECK refuses the write rather than storing a hybrid.
     */
    public void reconfigureOidc(String displayName, String issuerUri, String clientId,
            String clientSecretEncrypted, String scopes, ProviderFlags flags) {
        this.protocol = FederationProtocol.OIDC;
        this.issuerUri = issuerUri;
        this.clientId = clientId;
        this.clientSecretEncrypted = clientSecretEncrypted;
        this.scopes = scopes;
        apply(displayName, flags);
    }

    /** Repoint this SAML provider; the alias is immutable. See {@link #reconfigureOidc} on the other protocol's
     *  columns. */
    public void reconfigureSaml(String displayName, String idpEntityId, String ssoUrl, String signingCertificate,
            String nameIdFormat, ProviderFlags flags) {
        this.protocol = FederationProtocol.SAML;
        this.idpEntityId = idpEntityId;
        this.ssoUrl = ssoUrl;
        this.signingCertificate = signingCertificate;
        this.nameIdFormat = nameIdFormat;
        apply(displayName, flags);
    }

    private void apply(String displayName, ProviderFlags flags) {
        this.displayName = displayName;
        this.allowJitProvisioning = flags.allowJitProvisioning();
        this.linkByVerifiedEmail = flags.linkByVerifiedEmail();
        this.enabled = flags.enabled();
        this.presetId = flags.presetId();
    }
}
