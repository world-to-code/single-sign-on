package com.example.sso.federation.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An upstream OIDC identity provider a tenant's users can sign in through (Google, Okta, Azure AD, any OIDC
 * IdP). A {@code null} {@link #orgId} is a platform-tier provider (the super-admin's own login); a non-null one
 * belongs to that tenant. {@code alias} is the URL-safe handle in the login route
 * ({@code /api/auth/federation/{alias}/start}) and is unique within the tier. {@code clientSecretEncrypted} is
 * SecretCipher ciphertext — the plaintext is never stored, logged, audited, or returned; the login flow
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

    /** The OIDC issuer; discovery ({@code {issuer}/.well-known/openid-configuration}) drives the endpoints. */
    @Column(name = "issuer_uri", nullable = false)
    private String issuerUri;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    /** SecretCipher ciphertext of the OAuth client secret; never the plaintext. */
    @Column(name = "client_secret_encrypted", columnDefinition = "text")
    private String clientSecretEncrypted;

    /** Space-separated OAuth scopes requested at the upstream; {@code openid} is always required. */
    @Column(nullable = false)
    private String scopes;

    /** Whether a first-time federated user with no local account is provisioned just-in-time (else denied). */
    @Column(name = "allow_jit_provisioning", nullable = false)
    private boolean allowJitProvisioning;

    /** A disabled provider is not offered on the login screen and refuses to start a federated login. */
    @Column(nullable = false)
    private boolean enabled;

    /** Owning tenant, or {@code null} for a platform-tier provider. */
    public static IdentityProvider create(UUID orgId, String alias, String displayName, String issuerUri,
            String clientId, String clientSecretEncrypted, String scopes, boolean allowJitProvisioning,
            boolean enabled) {
        IdentityProvider provider = new IdentityProvider();
        provider.orgId = orgId;
        provider.alias = alias;
        provider.apply(displayName, issuerUri, clientId, clientSecretEncrypted, scopes, allowJitProvisioning, enabled);
        return provider;
    }

    /** Repoint this provider (intent-revealing mutation, not a JavaBean setter); the alias is immutable. */
    public void reconfigure(String displayName, String issuerUri, String clientId, String clientSecretEncrypted,
            String scopes, boolean allowJitProvisioning, boolean enabled) {
        apply(displayName, issuerUri, clientId, clientSecretEncrypted, scopes, allowJitProvisioning, enabled);
    }

    private void apply(String displayName, String issuerUri, String clientId, String clientSecretEncrypted,
            String scopes, boolean allowJitProvisioning, boolean enabled) {
        this.displayName = displayName;
        this.issuerUri = issuerUri;
        this.clientId = clientId;
        this.clientSecretEncrypted = clientSecretEncrypted;
        this.scopes = scopes;
        this.allowJitProvisioning = allowJitProvisioning;
        this.enabled = enabled;
    }
}
