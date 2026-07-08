package com.example.sso.audit;

import com.example.sso.audit.internal.domain.AuditEventRepository;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant scoping of the audit READ path. {@code audit_event} carries {@code org_id} but is deliberately
 * RLS-free (writes happen on browser-less/pre-context paths), so the isolation is enforced in the query:
 * a tenant tier ({@code orgId != null}) sees only that org's events, and the platform tier
 * ({@code orgId == null}) sees only the global (org-less) events — never another tenant's, and never all
 * tenants merged. Runs against Testcontainers so the {@code org_id} filter is exercised for real.
 */
class AuditTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    AuditService audit;
    @Autowired
    AuditEventRepository repository;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
    }

    @Test
    void aTenantTierSeesOnlyItsOwnOrgsEvents() {
        record(AuditType.AUTH_SUCCESS, "alice", orgA);
        record(AuditType.AUTH_SUCCESS, "bob", orgB);
        record(AuditType.AUTH_SUCCESS, "root", null);

        List<String> principals = principalsOf(audit.recent(orgA));

        assertThat(principals).contains("alice");
        assertThat(principals).doesNotContain("bob", "root");
    }

    @Test
    void thePlatformTierSeesOnlyGlobalEventsNotAnyTenant() {
        record(AuditType.AUTH_SUCCESS, "alice", orgA);
        record(AuditType.AUTH_SUCCESS, "root", null);

        List<String> principals = principalsOf(audit.recent(null));

        assertThat(principals).contains("root");
        assertThat(principals).doesNotContain("alice");
    }

    @Test
    void categoryReadsAreTenantScoped() {
        record(AuditType.AUTH_SUCCESS, "alice", orgA); // AUTHENTICATION category
        record(AuditType.AUTH_SUCCESS, "bob", orgB);

        List<String> principals = principalsOf(
                audit.recentByCategory(orgA, AuditType.AUTH_SUCCESS.getCategory()));

        assertThat(principals).contains("alice");
        assertThat(principals).doesNotContain("bob");
    }

    @Test
    void principalReadsAreTenantScopedSoASharedUsernameNeverLeaksAcrossOrgs() {
        // Usernames are unique only within an org (V68): the same "shared" login can exist in two tenants.
        record(AuditType.AUTH_SUCCESS, "shared", orgA);
        record(AuditType.SESSION_ADMIN_REVOKED, "shared", orgB);

        List<String> typesForA = audit.recentForPrincipal(orgA, "shared").stream()
                .map(AuditEntry::type).toList();

        assertThat(typesForA).contains(AuditType.AUTH_SUCCESS.name());
        assertThat(typesForA).doesNotContain(AuditType.SESSION_ADMIN_REVOKED.name());
    }

    private void record(AuditType type, String principal, UUID orgId) {
        audit.record(new AuditRecord(type, principal, true, "detail", null, orgId));
    }

    private List<String> principalsOf(List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::principal).toList();
    }
}
