package com.example.sso.config.internal;

import com.example.sso.tenancy.OrgContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

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
 */
public class OrgScopedRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository delegate;
    private final OrgContext orgContext;
    private final JdbcTemplate jdbc;

    public OrgScopedRegisteredClientRepository(RegisteredClientRepository delegate, OrgContext orgContext,
                                               JdbcTemplate jdbc) {
        this.delegate = delegate;
        this.orgContext = orgContext;
        this.jdbc = jdbc;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        delegate.save(registeredClient);
        // Stamp the owning tenant ONLY on first insert (org_id still null) — never re-assign an existing
        // client's tenant on a later update, which would silently move it to another org / the global tier.
        jdbc.update("update oauth2_registered_client set org_id = ? where id = ? and org_id is null",
                orgContext.currentOrg().orElse(null), registeredClient.getId());
    }

    @Override
    public RegisteredClient findById(String id) {
        return inHostTier(delegate.findById(id));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return inHostTier(delegate.findByClientId(clientId));
    }

    // Visible only when the client's owning org equals the request host's bound org (both null = a global
    // client on the bare platform host); otherwise hidden, so it cannot be used under another tenant's host.
    private RegisteredClient inHostTier(RegisteredClient client) {
        if (client == null) {
            return null;
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
