package com.example.sso.admin.internal.audit.application;

import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditSubjectType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AuditScope#permits(AuditEntry)} — the pure predicate deciding whether the acting
 * admin may see an audit entry. Rules: a super admin sees everything; anyone sees their OWN actions
 * (principal match); otherwise the entry's structured subject must fall inside the actor's scoped set
 * for that subject kind, and un-subjected ({@code NONE}) entries are hidden from a scoped admin.
 */
class AuditScopeTest {

    private static final UUID U1 = UUID.randomUUID();
    private static final UUID U2 = UUID.randomUUID();
    private static final UUID G1 = UUID.randomUUID();
    private static final UUID R1 = UUID.randomUUID();

    private AuditScope scoped() {
        return new AuditScope(false, "delegate", Set.of(U1), Set.of(G1), Set.of("app-1"), Set.of(R1));
    }

    private AuditEntry entry(AuditSubjectType type, String subjectId, String principal) {
        return new AuditEntry(1L, Instant.ofEpochSecond(0), principal, "USER_UPDATED",
                AuditCategory.ADMIN, true, "detail", type, subjectId);
    }

    @Test
    void aSuperAdminSeesEveryEntryIncludingUnsubjectedOnes() {
        AuditScope unscoped = new AuditScope(true, "root", Set.of(), Set.of(), Set.of(), Set.of());

        assertThat(unscoped.permits(entry(AuditSubjectType.NONE, null, "someone"))).isTrue();
        assertThat(unscoped.permits(entry(AuditSubjectType.USER, U2.toString(), "someone"))).isTrue();
    }

    @Test
    void anActorSeesTheirOwnActionsRegardlessOfSubject() {
        // principal == the actor, subject entirely outside their scope, even NONE.
        assertThat(scoped().permits(entry(AuditSubjectType.USER, U2.toString(), "delegate"))).isTrue();
        assertThat(scoped().permits(entry(AuditSubjectType.NONE, null, "delegate"))).isTrue();
    }

    @Test
    void aUserSubjectIsVisibleOnlyWhenInScope() {
        assertThat(scoped().permits(entry(AuditSubjectType.USER, U1.toString(), "other"))).isTrue();
        assertThat(scoped().permits(entry(AuditSubjectType.USER, U2.toString(), "other"))).isFalse();
    }

    @Test
    void aGroupSubjectIsVisibleOnlyWhenInScope() {
        assertThat(scoped().permits(entry(AuditSubjectType.GROUP, G1.toString(), "other"))).isTrue();
        assertThat(scoped().permits(entry(AuditSubjectType.GROUP, UUID.randomUUID().toString(), "other"))).isFalse();
    }

    @Test
    void anApplicationSubjectIsVisibleOnlyWhenInScope() {
        assertThat(scoped().permits(entry(AuditSubjectType.APPLICATION, "app-1", "other"))).isTrue();
        assertThat(scoped().permits(entry(AuditSubjectType.APPLICATION, "app-2", "other"))).isFalse();
    }

    @Test
    void aResourceSubjectIsVisibleOnlyWhenInScope() {
        assertThat(scoped().permits(entry(AuditSubjectType.RESOURCE, R1.toString(), "other"))).isTrue();
        assertThat(scoped().permits(entry(AuditSubjectType.RESOURCE, UUID.randomUUID().toString(), "other"))).isFalse();
    }

    @Test
    void anUnsubjectedEntryByAnotherPrincipalIsHiddenFromAScopedAdmin() {
        assertThat(scoped().permits(entry(AuditSubjectType.NONE, null, "other"))).isFalse();
    }

    @Test
    void aMalformedUuidSubjectIsDeniedNotCrashing() {
        assertThat(scoped().permits(entry(AuditSubjectType.USER, "not-a-uuid", "other"))).isFalse();
        assertThat(scoped().permits(entry(AuditSubjectType.GROUP, "", "other"))).isFalse();
        assertThat(scoped().permits(entry(AuditSubjectType.RESOURCE, null, "other"))).isFalse();
    }

    @Test
    void aNullPrincipalNeverMatchesTheActorAndFallsToSubjectScope() {
        assertThat(scoped().permits(entry(AuditSubjectType.NONE, null, null))).isFalse();
        assertThat(scoped().permits(entry(AuditSubjectType.USER, U1.toString(), null))).isTrue();
    }
}
