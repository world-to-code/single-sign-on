package com.example.sso.audit;

import com.example.sso.audit.internal.domain.AuditEventRepository;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Set;
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
        record(AuditType.AUTH_SUCCESS, "bob", orgB);
        record(AuditType.AUTH_SUCCESS, "root", null);

        List<String> principals = principalsOf(audit.recent(null));

        assertThat(principals).contains("root");
        assertThat(principals).doesNotContain("alice", "bob"); // never any tenant merged in
    }

    @Test
    void aTenantTierSurvivesTopNTruncationBehindAGlobalFlood() {
        // The scope MUST be in the query, not an after-fetch filter: a tenant with a few old events behind a
        // flood of >100 newer GLOBAL events must still see its own. A fetch-top-100-then-filter regression
        // would drop orgA's event out of the newest-100 window and return nothing here.
        record(AuditType.AUTH_SUCCESS, "lonely-a", orgA);
        for (int i = 0; i < 105; i++) { // exceed the findTop100 window with newer global events
            record(AuditType.AUTH_SUCCESS, "flood-" + i, null);
        }

        assertThat(principalsOf(audit.recent(orgA))).containsExactly("lonely-a");
    }

    @Test
    void categoryReadsAreScopedByBothOrgAndCategory() {
        record(AuditType.AUTH_SUCCESS, "alice-auth", orgA);          // AUTHENTICATION, orgA
        record(AuditType.SESSION_ADMIN_REVOKED, "alice-session", orgA); // SESSION, orgA
        record(AuditType.AUTH_SUCCESS, "bob", orgB);                 // AUTHENTICATION, orgB

        List<String> principals = principalsOf(
                audit.recentByCategory(orgA, AuditCategory.AUTHENTICATION));

        assertThat(principals).contains("alice-auth");
        assertThat(principals).doesNotContain("alice-session"); // category predicate is real
        assertThat(principals).doesNotContain("bob");           // org predicate is real
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

    @Test
    void thePlatformNullBranchOfPrincipalAndCategoryReadsSeesOnlyGlobalEvents() {
        // Each read's null branch dispatches to a SEPARATE repository method (…ByOrgIdIsNull…); make sure
        // neither the principal nor the category null-branch surfaces a tenant's rows.
        record(AuditType.AUTH_SUCCESS, "shared", null);       // global
        record(AuditType.AUTH_SUCCESS, "shared", orgA);       // tenant, same username (for the principal read)
        record(AuditType.AUTH_SUCCESS, "tenant-only", orgA);  // tenant, distinct (for the category read)

        assertThat(audit.recentForPrincipal(null, "shared")).hasSize(1); // only the global "shared", not orgA's
        assertThat(principalsOf(audit.recentByCategory(null, AuditCategory.AUTHENTICATION)))
                .contains("shared")
                .doesNotContain("tenant-only"); // global-only; the orgA rows are absent
    }

    @Test
    void categorySetReadsReturnOnlyThoseCategoriesScopedToTheOrg() {
        record(AuditType.AUTH_SUCCESS, "a-auth", orgA);             // AUTHENTICATION
        record(AuditType.SESSION_ADMIN_REVOKED, "a-session", orgA); // SESSION
        record(AuditType.AUTH_SUCCESS, "b-auth", orgB);             // AUTHENTICATION, other org

        List<String> principals = principalsOf(
                audit.recentByCategories(orgA, Set.of(AuditCategory.SESSION)));

        assertThat(principals).contains("a-session");
        assertThat(principals).doesNotContain("a-auth", "b-auth"); // category AND org predicates both real
    }

    @Test
    void anEmptyCategorySetReturnsNoEvents() {
        record(AuditType.AUTH_SUCCESS, "a", orgA);

        assertThat(audit.recentByCategories(orgA, Set.of())).isEmpty(); // never falls through to unfiltered
    }

    private void record(AuditType type, String principal, UUID orgId) {
        audit.record(new AuditRecord(type, principal, true, "detail", null, orgId));
    }

    private List<String> principalsOf(List<AuditEntry> entries) {
        return entries.stream().map(AuditEntry::principal).toList();
    }
}
