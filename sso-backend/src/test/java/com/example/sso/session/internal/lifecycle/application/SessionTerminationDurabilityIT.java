package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline of the durable backstop: a termination that the in-thread retry could not complete is persisted
 * in Redis and, when re-driven, actually deletes the real session — not merely audited. Proves the chain the
 * unit tests mock away (registry round-trip → re-driver → real Redis session), scoped to the target org, and
 * that the member (user-id) path resolves the username on a browser-less thread with no security/tenant context
 * bound. Drives the re-drive directly rather than calling {@code sweeper.sweep()} — the live {@code @Scheduled}
 * sweeper races it for the single-driver lock in-JVM; the sweeper's own lock/loop is covered by its unit test.
 * Runs OUTSIDE a transaction so the real Redis session deletion (and its downstream events) fire as in prod.
 */
class SessionTerminationDurabilityIT extends AbstractIntegrationTest {

    private static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    @Autowired
    RedisIndexedSessionRepository sessions;
    @Autowired
    UserService users;
    @Autowired
    OrganizationService organizations;
    @Autowired
    SessionTerminationRetryRegistry registry;
    @Autowired
    SessionTerminationRedriver redriver;

    private final List<String> createdSessions = new ArrayList<>();
    private UUID userId;
    private UUID orgA;
    private UUID orgB;
    private String username;

    @BeforeEach
    void seed() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        username = "sess-dura-" + s;
        userId = users.createUser(new NewUser(username, username + "@example.com", username, "S3cret!pw9",
                Set.of("ROLE_USER"))).getId();
        orgA = organizations.create(new NewOrganization("sessdura-a-" + s, "Org A")).id();
        orgB = organizations.create(new NewOrganization("sessdura-b-" + s, "Org B")).id();
        organizations.addMember(orgA, userId);
        organizations.addMember(orgB, userId);
    }

    @AfterEach
    void cleanup() {
        createdSessions.forEach(id -> sessions.deleteById(id));
        createdSessions.clear();
        ownerJdbc().update("delete from organization where id = ?", orgA); // cascades memberships
        ownerJdbc().update("delete from organization where id = ?", orgB);
        users.delete(userId);
    }

    @Test
    void aPersistedTerminationIsReadBackReDrivenAndEndsOnlyTheTargetOrgsSession() {
        String sessionA = createOrgSession(orgA);
        String sessionB = createOrgSession(orgB);
        SessionTerminationRequest deferred = SessionTerminationRequest.forUser(username, orgA);
        registry.schedule(deferred, 0, 1L); // enqueued past-due, as ResilientSessionTermination would on hand-off

        // The sweep loop, deterministically: read the due entry back from Redis, re-drive it, drop it on success.
        SessionTerminationRetryRegistry.Pending pending = registry.pending(deferred.key()).orElseThrow();
        assertThat(registry.due(5_000L, 1000)).contains(deferred.key()); // it is due
        redriver.redrive(pending.request());
        registry.remove(deferred.key());

        assertThat(sessions.findByPrincipalName(username))
                .as("the re-drive deleted the org-A session; the org-B session survives")
                .containsKey(sessionB)
                .doesNotContainKey(sessionA);
        assertThat(registry.pending(deferred.key())).as("a delivered entry stops being tracked").isEmpty();
    }

    @Test
    void theMemberPathResolvesTheUserIdAndEndsTheSessionWithNoContextBound() {
        String sessionA = createOrgSession(orgA);

        int ended = redriver.redrive(SessionTerminationRequest.forMember(userId, orgA)); // no security/tenant context

        assertThat(ended).isEqualTo(1);
        assertThat(sessions.findByPrincipalName(username)).doesNotContainKey(sessionA);
    }

    /** Persists a real Redis session for the user carrying the {@code ORG_<orgId>} marker in its SecurityContext. */
    private String createOrgSession(UUID orgId) {
        var session = sessions.createSession();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority(Factors.ORG_PREFIX + orgId)));
        session.setAttribute(SECURITY_CONTEXT_ATTR, new SecurityContextImpl(auth));
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);
        sessions.save(session);
        createdSessions.add(session.getId());
        return session.getId();
    }
}
