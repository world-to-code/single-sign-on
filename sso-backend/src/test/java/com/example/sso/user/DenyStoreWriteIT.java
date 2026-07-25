package com.example.sso.user;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.OrgPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the native {@code insertIfAbsent} / {@code findId} write queries against a real database — the
 * service unit tests mock the repositories, so the SQL itself (and its ON CONFLICT idempotency) is only proven
 * here. Runs as platform (RLS is covered by {@code PermissionDenyRlsIT}); this asserts the SQL is correct and a
 * repeat insert is a no-op returning the same row.
 */
class DenyStoreWriteIT extends AbstractIntegrationTest {

    @Autowired
    OrgPermissionDenyRepository orgDenies;
    @Autowired
    PrincipalPermissionDenyRepository principalDenies;
    @Autowired
    OrgContext orgContext;

    private String pattern;

    @AfterEach
    void cleanup() {
        if (pattern != null) {
            ownerJdbc().update("delete from org_permission_deny where pattern = ?", pattern);
            ownerJdbc().update("delete from principal_permission_deny where pattern = ?", pattern);
        }
    }

    @Test
    @Transactional
    void anOrgDenyInsertIsCreatedThenIdempotent() {
        pattern = "user:read#" + UUID.randomUUID().toString().substring(0, 8);
        orgContext.runAsPlatform(() -> {
            assertThat(orgDenies.insertIfAbsent(null, pattern, null, null)).isEqualTo(1); // created (platform veto)
            UUID id = orgDenies.findId(null, pattern).orElseThrow();
            assertThat(orgDenies.insertIfAbsent(null, pattern, null, null)).isZero();      // ON CONFLICT DO NOTHING
            assertThat(orgDenies.findId(null, pattern)).contains(id);                      // same row
        });
    }

    @Test
    @Transactional
    void aPrincipalDenyInsertIsCreatedThenIdempotent() {
        pattern = "role:delete#" + UUID.randomUUID().toString().substring(0, 8);
        UUID subjectId = UUID.randomUUID();
        orgContext.runAsPlatform(() -> {
            assertThat(principalDenies.insertIfAbsent("ROLE", subjectId, null, pattern, null, null)).isEqualTo(1);
            UUID id = principalDenies.findId(DenySubjectType.ROLE, subjectId, pattern).orElseThrow();
            assertThat(principalDenies.insertIfAbsent("ROLE", subjectId, null, pattern, null, null)).isZero();
            assertThat(principalDenies.findId(DenySubjectType.ROLE, subjectId, pattern)).contains(id);
        });
    }
}
