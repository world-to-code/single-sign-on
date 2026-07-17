package com.example.sso.admin.internal.audit.application;

import com.example.sso.audit.AuditCategory;
import com.example.sso.user.rbac.Permissions;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-category read authorization evaluated against the acting admin's authorities. The bean reads the
 * already-expanded authority set (the {@code audit:read} macro is expanded upstream at authority assembly), so
 * it checks the concrete {@code audit:read:<category>} / {@code audit:read:pii} perms directly.
 */
class AuditAccessPolicyTest {

    private final AuditAccessPolicy policy = new AuditAccessPolicy();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void actingWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, authorities));
    }

    @Test
    void permittedCategoriesReflectsOnlyHeldPerms() {
        actingWith(Permissions.AUDIT_READ_SESSION, Permissions.AUDIT_READ_ADMIN);

        assertThat(policy.permittedCategories())
                .containsExactlyInAnyOrder(AuditCategory.SESSION, AuditCategory.ADMIN);
    }

    @Test
    void canReadASpecificCategoryRequiresThatCategorysPerm() {
        actingWith(Permissions.AUDIT_READ_SESSION);

        assertThat(policy.canRead(AuditCategory.SESSION)).isTrue();
        assertThat(policy.canRead(AuditCategory.ADMIN)).isFalse();
    }

    @Test
    void canReadTheAllViewNeedsAnyAuditPerm() {
        actingWith(Permissions.AUDIT_READ_SESSION);
        assertThat(policy.canRead(null)).isTrue();

        actingWith("user:read"); // no audit perm at all
        assertThat(policy.canRead(null)).isFalse();
        assertThat(policy.permittedCategories()).isEmpty();
    }

    @Test
    void piiIsGatedByItsOwnPerm() {
        actingWith(Permissions.AUDIT_READ_SESSION);
        assertThat(policy.canReadPii()).isFalse();

        actingWith(Permissions.AUDIT_READ_SESSION, Permissions.AUDIT_READ_PII);
        assertThat(policy.canReadPii()).isTrue();
    }

    @Test
    void theAuditReadMacroGrantsEveryCategory() {
        // Completeness guard: the macro holder must read EVERY AuditCategory. A category missing from the
        // permission map (AuditAccessPolicy.CATEGORY_PERMISSION) or from the macro (Permissions.AUDIT_READ_MACRO)
        // would be silently invisible to everyone — this fails the moment a new category isn't wired through both.
        actingWith(Permissions.expandImplied(Set.of(Permissions.AUDIT_READ)).toArray(String[]::new));

        assertThat(policy.permittedCategories())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(AuditCategory.class));
    }

    @Test
    void anUnauthenticatedContextSeesNothing() {
        SecurityContextHolder.clearContext();

        assertThat(policy.permittedCategories()).isEmpty();
        assertThat(policy.canRead(null)).isFalse();
        assertThat(policy.canReadPii()).isFalse();
    }
}
