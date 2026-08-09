package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.OidcAuthorizations;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Service;

/**
 * Default {@link OidcAuthorizations}.
 *
 * <p>The lookup is raw SQL because Spring's {@link OAuth2AuthorizationService} cannot answer "which
 * authorizations does this principal hold" — it finds by id or by token value only. Removal still goes
 * through the service rather than a bare {@code DELETE}, so whatever it does around a removal keeps
 * happening if the implementation is ever swapped.
 *
 * <p><b>Scoped by the client's owning tenant.</b> {@code oauth2_authorization} has no organization of its
 * own and its {@code principal_name} is a bare username, which is unique only WITHIN an organization — so
 * revoking by name alone would delete a same-named stranger's grants in another tenant. The row's tenant is
 * therefore the one owning the application it was issued for.
 *
 * <p>The org-agnostic admin-console client is deliberately outside that scope and is not revoked here. It is
 * the one client whose registration carries no tenant, so including it would mean deleting every tenant's
 * console grants whenever any same-named user anywhere changed access. Leaving it is safe rather than
 * convenient: the console client has no {@code refresh_token} grant, and its access token is worthless
 * without the live session that the same access change has just ended.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class JdbcOidcAuthorizations implements OidcAuthorizations {

    /** Authorizations held by this principal against applications of one tenant (NULL = the platform tier). */
    private static final String IDS_FOR_PRINCIPAL_IN_TIER = """
            SELECT a.id FROM oauth2_authorization a
              JOIN oauth2_registered_client c ON c.id = a.registered_client_id
             WHERE a.principal_name = ?
               AND c.org_id IS NOT DISTINCT FROM ?
            """;

    private final JdbcTemplate jdbc;
    private final OAuth2AuthorizationService authorizations;
    private final OrgContext orgContext;

    @Override
    public int revokeForUser(String username, UUID orgId) {
        List<String> ids = jdbc.queryForList(IDS_FOR_PRINCIPAL_IN_TIER, String.class, username, orgId);
        if (ids.isEmpty()) {
            return 0;
        }
        // BIND the target tenant, do not merely pass it. Reading an authorization back resolves its client
        // through the ORG-SCOPED client repository, which hides a client belonging to any tenant other than
        // the bound one — and this runs AFTER_COMMIT, on a thread bound to whatever the request left behind
        // or to nothing at all. Without the binding the read throws and the grants quietly survive.
        return orgContext.callInOrg(orgId, () -> revokeAll(ids));
    }

    private int revokeAll(List<String> ids) {
        int revoked = 0;
        for (String id : ids) {
            OAuth2Authorization authorization = authorizations.findById(id);
            // Gone already (a concurrent revocation, or an expiring token the store cleaned up) — the
            // desired state is what matters, so this is a no-op rather than a failure.
            if (authorization != null) {
                authorizations.remove(authorization);
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info("Revoked {} OAuth2 authorization(s) for a user whose access changed", revoked);
        }
        return revoked;
    }
}
