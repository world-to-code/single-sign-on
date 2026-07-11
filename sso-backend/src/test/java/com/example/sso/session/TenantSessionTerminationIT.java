package com.example.sso.session;

import com.example.sso.session.internal.lifecycle.application.SessionManagerImpl;

import com.example.sso.authpolicy.Factors;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserService;
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
 * End-to-end proof of tenant-aware session termination through the REAL wiring the unit tests mock away:
 * a membership revocation / org suspension fires {@code OrganizationAccessRevokedEvent}, whose AFTER_COMMIT
 * listener (SessionManagerImpl) reads each Redis session's stored SecurityContext and deletes ONLY the ones
 * carrying that org's {@code ORG_} marker. Runs OUTSIDE a transaction so the AFTER_COMMIT phase actually
 * fires (a wrapping test tx would defer it) — exactly the class of wiring bug a Mockito unit test can't catch.
 */
class TenantSessionTerminationIT extends AbstractIntegrationTest {

    private static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    @Autowired
    RedisIndexedSessionRepository sessions;
    @Autowired
    UserService users;
    @Autowired
    OrganizationService organizations;

    private final List<String> createdSessions = new ArrayList<>();
    private UUID userId;
    private UUID orgA;
    private UUID orgB;
    private String username;

    @BeforeEach
    void seed() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        username = "sess-term-" + s;
        userId = users.createUser(new NewUser(username, username + "@example.com", username, "S3cret!pw9",
                Set.of("ROLE_USER"))).getId();
        orgA = organizations.create(new NewOrganization("sessterm-a-" + s, "Org A")).id();
        orgB = organizations.create(new NewOrganization("sessterm-b-" + s, "Org B")).id();
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
    void revokingMembershipEndsThatOrgsSessionButLeavesTheUsersOtherOrgSession() {
        String sessionA = createOrgSession(orgA);
        String sessionB = createOrgSession(orgB);
        assertThat(sessions.findByPrincipalName(username)).containsKeys(sessionA, sessionB);

        organizations.removeMember(orgA, userId); // @Transactional commit -> AFTER_COMMIT fires the listener

        assertThat(sessions.findByPrincipalName(username))
                .as("the org-A session is deleted, the org-B session (still a member) survives")
                .containsKey(sessionB)
                .doesNotContainKey(sessionA);
    }

    @Test
    void suspendingAnOrgEndsItsMembersSessionButNotTheirOtherOrgs() {
        String sessionA = createOrgSession(orgA);
        String sessionB = createOrgSession(orgB);

        organizations.update(orgB, "Org B", OrganizationStatus.SUSPENDED); // fans out per member -> AFTER_COMMIT

        assertThat(sessions.findByPrincipalName(username))
                .as("suspending org B ends its session; the org-A session survives")
                .containsKey(sessionA)
                .doesNotContainKey(sessionB);
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
