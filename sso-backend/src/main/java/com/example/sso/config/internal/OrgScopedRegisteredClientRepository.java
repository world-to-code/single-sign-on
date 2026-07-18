package com.example.sso.config.internal;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.tenancy.OrgContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wraps Spring's {@link RegisteredClientRepository} to bind each OIDC client to its owning tenant, making
 * the per-tenant issuer a real isolation boundary rather than an illusion. A client owned by org A is
 * visible (and thus usable) ONLY when the request host resolved to org A; a GLOBAL/platform client
 * ({@code org_id} null) only under the bare platform host. Because the issuer and signing key are derived
 * from the request host, without this a client's credentials presented at another tenant's host would mint
 * a token signed with THAT tenant's key under its issuer.
 *
 * <p>Enforced in code (not RLS): Spring's {@code save()} inserts the row before the org can be stamped, and
 * an org-bound connection under FORCE RLS would reject that insert. A client whose {@code org_id} does not
 * match the request's bound org is returned as {@code null} — Spring then treats it as an unknown client
 * ({@code invalid_client}).
 *
 * <p>Per-tenant client_ids: a {@code client_id} is unique PER TENANT (two orgs may each own "acme"). Spring's
 * {@link JdbcRegisteredClientRepository} enforces GLOBAL client_id uniqueness with a hardcoded {@code COUNT}
 * query we cannot override, so a new client is inserted under a placeholder client_id (its own globally-unique
 * internal id) to pass that assert, then the real client_id and owning org are set in one atomic UPDATE.
 * Per-tier uniqueness is enforced by the tier-aware partial indexes (V109), which fire on that UPDATE.
 */
public class OrgScopedRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final OrgContext orgContext;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrgScopedRegisteredClientRepository(RegisteredClientRepository delegate, OrgContext orgContext,
                                               JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.delegate = delegate;
        this.orgContext = orgContext;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        if (exists(registeredClient.getId())) {
            delegate.save(registeredClient); // update path — client_id/org already set, no uniqueness assert
            return;
        }
        // INSERT: land a placeholder client_id (the row's own internal id — globally unique) so Spring's GLOBAL
        // client_id uniqueness assert cannot false-positive on a client_id another tenant legitimately owns, then
        // set the real client_id AND stamp the owning tenant in one UPDATE. Stamping only while org_id is null
        // keeps a later update from re-assigning an existing client's tenant. The (org_id, client_id) / global
        // partial indexes (V109) enforce per-tier uniqueness on this UPDATE. The two statements run in ONE
        // transaction (joining the caller's, or its own for a non-transactional seeder) so a failed UPDATE never
        // commits an orphan placeholder-client_id row.
        tx.executeWithoutResult(status -> {
            delegate.save(RegisteredClient.from(registeredClient).clientId(registeredClient.getId()).build());
            jdbc.update("update oauth2_registered_client set client_id = ?, org_id = ? "
                            + "where id = ? and org_id is null",
                    registeredClient.getClientId(), orgContext.currentOrg().orElse(null),
                    registeredClient.getId());
        });
    }

    private boolean exists(String id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from oauth2_registered_client where id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    @Override
    public RegisteredClient findById(String id) {
        return inHostTier(delegate.findById(id));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        // The first-party admin console is host-AGNOSTIC: every tenant admin enters it from THEIR OWN subdomain
        // (host-bound session), so it must resolve at the platform host AND every tenant host. Safe because it
        // is a PUBLIC client (PKCE, no secret to leak across hosts) whose interactive redirect is validated
        // same-origin (AdminConsoleRedirectUriValidator) — the cross-tenant-mint risk this scoping guards is a
        // CONFIDENTIAL client presenting its secret at a foreign host, which does not apply here.
        if (AdminPortalSeeder.CLIENT_ID.equals(clientId)) {
            return delegate.findByClientId(clientId);
        }
        // client_id is unique only PER TENANT: resolve the id WITHIN the request host's tier (matching org, or
        // no org for a global client) — NOT delegate.findByClientId, which returns an ARBITRARY row when two
        // tenants share the client_id and would hide the caller's own client behind another tenant's.
        String id = jdbc.query(
                "select id from oauth2_registered_client where client_id = ? "
                        + "and org_id is not distinct from cast(? as uuid)",
                rs -> rs.next() ? rs.getString("id") : null, clientId, orgContext.currentOrg().orElse(null));
        return id == null ? null : delegate.findById(id);
    }

    // Visible only when the client's owning org equals the request host's bound org (both null = a global
    // client on the bare platform host); otherwise hidden, so it cannot be used under another tenant's host.
    private RegisteredClient inHostTier(RegisteredClient client) {
        if (client == null) {
            return null;
        }
        if (AdminPortalSeeder.CLIENT_ID.equals(client.getClientId())) {
            return client; // host-agnostic admin console (see findByClientId)
        }
        UUID clientOrg = clientOrg(client.getId());
        UUID hostOrg = orgContext.currentOrg().orElse(null);
        return Objects.equals(clientOrg, hostOrg) ? client : null;
    }

    private UUID clientOrg(String id) {
        return jdbc.query("select org_id from oauth2_registered_client where id = ?",
                rs -> rs.next() ? rs.getObject("org_id", UUID.class) : null, id);
    }
}
