package com.example.sso.federation;

import java.util.Optional;
import java.util.UUID;

/**
 * The durable link between an upstream identity — the {@code issuer} that minted it plus the stable
 * {@code subject} ({@code sub}) it asserts — and the local account it belongs to, within ONE organization.
 *
 * <p>Without it a federated login can only match on email, which is the wrong key twice over: an upstream
 * address may change (the account is then re-provisioned as a duplicate, losing its groups and roles) and an
 * address may be reassigned to a different person (who would inherit the previous holder's account). The
 * {@code subject} is the identifier OIDC actually guarantees to be stable.
 *
 * <p>Keyed on the ISSUER, never the provider alias: an alias is a tenant-chosen label that can be repointed at
 * a different IdP, and links keyed on it would silently carry over to the new upstream, where a colliding
 * {@code sub} would inherit the account.
 *
 * <p>Scoped per organization, always: the same upstream {@code subject} may legitimately map to different
 * local accounts in different tenants, because an organization owns its own {@code app_user} rows (see
 * {@code V68__app_user_org_uniqueness.sql}). Lookups therefore key on {@code orgId} first, and RLS backs that
 * up at the storage layer. Implementations bind the tenant context themselves — the federation login flow runs
 * pre-authentication, when no {@code OrgContext} is bound by the request filter.
 */
public interface FederatedIdentityLinks {

    /** The local user this upstream identity is linked to in {@code orgId}, if it has been linked. */
    Optional<UUID> findLinkedUser(UUID orgId, String issuer, String subject);

    /**
     * Whether {@code userId} already holds an identity at {@code issuer}. A second subject from the same
     * upstream must not be able to claim the account by email — that is how a recycled address would inherit
     * the previous holder's account, and how the wrong binding would then become permanent.
     */
    boolean isLinked(UUID orgId, String issuer, UUID userId);

    /**
     * Records the link so the next login resolves by {@code subject} rather than by email. Idempotent: a
     * concurrent first login for the same identity is absorbed rather than failing. Callers record a link only
     * after the account has passed every authorization gate.
     */
    void link(UUID orgId, String issuer, String subject, String providerAlias, UUID userId);

    /**
     * Drops every link an organization holds for {@code issuer} — used when a provider is deleted or repointed
     * at a different upstream, so its identities do not survive to be re-resolved against a new IdP.
     */
    void unlinkAll(UUID orgId, String issuer);
}
