package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederatedIdentityAdminService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unlink surface against a REAL database. Its unit test mocks the repository, so nothing there executes
 * what Postgres actually holds: the STRICT per-tier RLS on {@code federated_identity}, the org predicate, and
 * — the part that would fail silently — whether the tenant context bound by the request filter reaches the
 * connection this {@code @Transactional} service holds. If it does not, every lookup returns empty and admin
 * unlink is a permanent 404, which matters because unlinking is the ONLY recovery from the login path's
 * fail-closed guards.
 *
 * <p>Deliberately NOT {@code @Transactional} — an ambient test transaction would supply the very binding under
 * test.
 */
class FederatedIdentityAdminServiceIT extends AbstractIntegrationTest {

    private static final String ISSUER = "https://upstream.test";

    @Autowired
    FederatedIdentityAdminService service;

    @Autowired
    OrganizationService organizations;

    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    private UUID newOrg() {
        UUID id = organizations.create(new NewOrganization("fed-admin-" + suffix(), "Fed admin")).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private UUID newUser(UUID orgId) {
        UUID id = UUID.randomUUID();
        String handle = "fa-" + suffix() + "@example.test";
        ownerJdbc().update("""
                insert into app_user (id, username, email, display_name, org_id)
                values (?, ?, ?, 'Probe', ?)""", id, handle, handle, orgId);
        cleanups.add(() -> ownerJdbc().update("delete from app_user where id = ?", id));
        return id;
    }

    private UUID seedLink(UUID orgId, UUID userId) {
        UUID id = UUID.randomUUID();
        ownerJdbc().update("""
                insert into federated_identity (id, org_id, issuer, subject, provider_alias, user_id)
                values (?, ?, ?, ?, 'okta', ?)""", id, orgId, ISSUER, "sub-" + suffix(), userId);
        cleanups.add(() -> ownerJdbc().update("delete from federated_identity where id = ?", id));
        return id;
    }

    private long rowsFor(UUID identityId) {
        return ownerJdbc().queryForObject(
                "select count(*) from federated_identity where id = ?", Long.class, identityId);
    }

    @Test
    void listsAndRevokesAnIdentityOfTheActingTenant() {
        UUID org = newOrg();
        UUID user = newUser(org);
        UUID identity = seedLink(org, user);

        assertThat(orgContext.callInOrg(org, () -> service.forUser(user))).hasSize(1);
        orgContext.runInOrg(org, () -> service.unlink(user, identity));

        assertThat(rowsFor(identity)).isZero();
    }

    /** The org predicate and RLS must both hold: another tenant's identity is not merely hidden, it survives. */
    @Test
    void cannotRevokeAnotherTenantsIdentity() {
        UUID orgA = newOrg();
        UUID orgB = newOrg();
        UUID userB = newUser(orgB);
        UUID identityB = seedLink(orgB, userB);

        assertThatThrownBy(() -> orgContext.runInOrg(orgA, () -> service.unlink(userB, identityB)))
                .isInstanceOf(NotFoundException.class);
        assertThat(rowsFor(identityB)).isOne();
    }

    /** Pairing an in-scope account with someone else's identity id must not resolve. */
    @Test
    void cannotRevokeAnIdentityThatBelongsToADifferentAccount() {
        UUID org = newOrg();
        UUID owner = newUser(org);
        UUID other = newUser(org);
        UUID identity = seedLink(org, owner);

        assertThatThrownBy(() -> orgContext.runInOrg(org, () -> service.unlink(other, identity)))
                .isInstanceOf(NotFoundException.class);
        assertThat(rowsFor(identity)).isOne();
    }

    @Test
    void anOrglessCallerIsRefused() {
        UUID org = newOrg();
        UUID user = newUser(org);

        assertThatThrownBy(() -> service.forUser(user)).isInstanceOf(ForbiddenException.class);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
