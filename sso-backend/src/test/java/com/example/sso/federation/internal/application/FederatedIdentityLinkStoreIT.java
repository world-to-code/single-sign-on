package com.example.sso.federation.internal.application;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exercises the link store against a REAL database and its real unique constraint. The service-level tests mock
 * this store and the RLS test drives raw JDBC, so nothing else would notice that a concurrent first login
 * poisons its own transaction: absorbing the constraint violation in Java is not enough if JPA has already
 * marked the transaction rollback-only, because the commit then fails after the catch block.
 *
 * <p>Deliberately NOT {@code @Transactional} — an ambient test transaction would change exactly the behaviour
 * under test.
 */
class FederatedIdentityLinkStoreIT extends AbstractIntegrationTest {

    private static final String ISSUER = "https://upstream.test";
    private static final String ALIAS = "okta";

    @Autowired
    FederatedIdentityLinkStore store;

    @Autowired
    OrganizationService organizations;

    @Autowired
    OrgContext orgContext;

    private UUID orgId;
    private UUID userId;
    private final List<UUID> seededUsers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        seededUsers.forEach(id -> {
            ownerJdbc().update("delete from federated_identity where user_id = ?", id);
            ownerJdbc().update("delete from app_user where id = ?", id);
        });
        seededUsers.clear();
        if (orgId != null) {
            ownerJdbc().update("delete from organization where id = ?", orgId);
            orgId = null;
        }
    }

    private void givenAnAccount() {
        if (orgId == null) {
            orgId = organizations.create(new NewOrganization("link-store-" + suffix(), "Link store")).id();
        }
        userId = UUID.randomUUID();
        seededUsers.add(userId);
        String handle = "link-" + suffix() + "@example.test";
        ownerJdbc().update("""
                insert into app_user (id, username, email, display_name, org_id)
                values (?, ?, ?, 'Probe', ?)""", userId, handle, handle, orgId);
    }

    private boolean link(String subject) {
        return orgContext.callInOrg(orgId, () -> store.link(orgId, ISSUER, subject, ALIAS, userId));
    }

    @Test
    void linksAnIdentityAndResolvesItBack() {
        givenAnAccount();
        String subject = "sub-" + suffix();

        link(subject);

        assertThat(orgContext.callInOrg(orgId, () -> store.findUserId(orgId, ISSUER, subject))).contains(userId);
        assertThat(orgContext.callInOrg(orgId, () -> store.isLinked(orgId, ISSUER, userId))).isTrue();
    }

    /**
     * The race two concurrent first logins run into. Both resolved the SAME account, so the row that wins says
     * exactly what the loser wanted it to say — the loser must return quietly, not fail its sign-in.
     */
    @Test
    void linkingAnIdentityThatIsAlreadyLinkedIsAbsorbed() {
        givenAnAccount();
        String subject = "sub-" + suffix();
        link(subject);

        assertThatCode(() -> link(subject)).doesNotThrowAnyException();

        assertThat(link(subject)).isTrue(); // the winning row already says what this call wanted it to say
        assertThat(orgContext.callInOrg(orgId, () -> store.findUserId(orgId, ISSUER, subject))).contains(userId);
    }

    /**
     * A SECOND subject from the same upstream claiming an account that already holds an identity there. The
     * application checks this first, but two concurrent callbacks both read "not linked" and both insert —
     * different subjects, so the (org, issuer, subject) index does not stop them. The account-level constraint
     * does, and the loser must be told it did not get the link rather than proceeding as if it had.
     */
    @Test
    void aSecondSubjectCannotAlsoClaimTheSameAccount() {
        givenAnAccount();
        link("sub-first-" + suffix());

        boolean linked = link("sub-second-" + suffix());

        assertThat(linked).isFalse();
    }

    @Test
    void twoAccountsMayEachHoldTheirOwnIdentityAtTheSameUpstream() {
        givenAnAccount();
        UUID first = userId;
        link("sub-first-" + suffix());
        givenAnAccount(); // a second account in the same org

        assertThat(link("sub-other-" + suffix())).isTrue();
        assertThat(orgContext.callInOrg(orgId, () -> store.isLinked(orgId, ISSUER, first))).isTrue();
    }

    @Test
    void retiringAnUpstreamRemovesItsIdentities() {
        givenAnAccount();
        link("sub-a-" + suffix());
        link("sub-b-" + suffix());

        List<UUID> retired = orgContext.callInOrg(orgId, () -> store.unlinkAll(orgId, ISSUER, ALIAS));

        // The RETURN is load-bearing: it is what the caller terminates sessions from, so reading the ids after
        // the delete (always empty) would silently make revocation a no-op.
        assertThat(retired).containsExactly(userId);
        assertThat(orgContext.callInOrg(orgId, () -> store.isLinked(orgId, ISSUER, userId))).isFalse();
    }

    /** Retiring one upstream must not touch identities minted by another. */
    @Test
    void retiringAnUpstreamLeavesAnotherUpstreamsIdentitiesAlone() {
        givenAnAccount();
        String kept = "sub-kept-" + suffix();
        link("sub-retired-" + suffix());
        orgContext.runInOrg(orgId, () -> store.link(orgId, "https://other.test", kept, "other", userId));

        orgContext.runInOrg(orgId, () -> store.unlinkAll(orgId, ISSUER, ALIAS));

        assertThat(orgContext.callInOrg(orgId, () -> store.findUserId(orgId, "https://other.test", kept)))
                .contains(userId);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
