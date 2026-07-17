package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditAccessPolicy;
import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.audit.AuditActorType;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.crypto.RsaKeyService;
import com.example.sso.saml.credential.SamlCredentialService;
import com.example.sso.scim.ScimTokenService;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The audit read facade layers three protections: the ALL view is restricted to the reader's permitted
 * categories (never the full set), a single category is read directly (the controller already gated it), and the
 * actor's PII is redacted unless the reader holds {@code audit:read:pii}.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private AuditService auditService;
    @Mock private ScimTokenService scimTokenService;
    @Mock private RsaKeyService rsaKeyService;
    @Mock private SamlCredentialService samlCredentialService;
    @Mock private AdminAccessPolicy accessPolicy;
    @Mock private AuditAccessPolicy auditAccessPolicy;
    @InjectMocks private AdminService adminService;

    private final UUID org = UUID.randomUUID();

    private AuditEntry entryWithPii() {
        return new AuditEntry(1L, Instant.EPOCH, "alice", "AUTH_SUCCESS", AuditCategory.SESSION, true, "detail",
                AuditSubjectType.NONE, null, AuditActorType.USER, UUID.randomUUID(), "alice@acme.test", "Alice A",
                "203.0.113.1", "ua", "device", "req-1", null, AuditSeverity.INFO);
    }

    private void unscoped() {
        when(accessPolicy.actingOrg()).thenReturn(org);
        when(accessPolicy.currentAuditScope())
                .thenReturn(new AuditScope(true, "root", Set.of(), Set.of(), Set.of(), Set.of()));
    }

    @Test
    void anUnpermittedCategoryIsForbiddenBeforeAnyRead() {
        when(auditAccessPolicy.canRead(AuditCategory.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> adminService.recentAudit(AuditCategory.ADMIN, 0, 20))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(auditService); // denied before touching the store
    }

    @Test
    void theAllViewIsRestrictedToTheReadersPermittedCategories() {
        when(auditAccessPolicy.canRead(null)).thenReturn(true);
        unscoped();
        Set<AuditCategory> permitted = Set.of(AuditCategory.SESSION);
        when(auditAccessPolicy.permittedCategories()).thenReturn(permitted);
        lenient().when(auditAccessPolicy.canReadPii()).thenReturn(true);
        when(auditService.recentByCategories(org, permitted)).thenReturn(List.of(entryWithPii()));

        Page<AuditEntry> page = adminService.recentAudit(null, 0, 20);

        assertThat(page.items()).hasSize(1);
        verify(auditService).recentByCategories(org, permitted);
        verifyNoMoreInteractions(auditService); // never the unfiltered recent()
    }

    @Test
    void theAllViewAlsoRedactsPiiWithoutTheGrant() {
        when(auditAccessPolicy.canRead(null)).thenReturn(true);
        unscoped();
        Set<AuditCategory> permitted = Set.of(AuditCategory.SESSION);
        when(auditAccessPolicy.permittedCategories()).thenReturn(permitted);
        when(auditAccessPolicy.canReadPii()).thenReturn(false);
        when(auditService.recentByCategories(org, permitted)).thenReturn(List.of(entryWithPii()));

        AuditEntry shown = adminService.recentAudit(null, 0, 20).items().getFirst();

        assertThat(shown.actorEmail()).isNull();
        assertThat(shown.remoteIp()).isNull();
    }

    @Test
    void aSpecificCategoryReadsThatCategoryOnly() {
        when(auditAccessPolicy.canRead(AuditCategory.ADMIN)).thenReturn(true);
        unscoped();
        lenient().when(auditAccessPolicy.canReadPii()).thenReturn(true);
        when(auditService.recentByCategory(org, AuditCategory.ADMIN)).thenReturn(List.of());

        adminService.recentAudit(AuditCategory.ADMIN, 0, 20);

        verify(auditService).recentByCategory(org, AuditCategory.ADMIN);
    }

    @Test
    void actorPiiIsRedactedWithoutTheGrant() {
        when(auditAccessPolicy.canRead(AuditCategory.SESSION)).thenReturn(true);
        unscoped();
        when(auditAccessPolicy.canReadPii()).thenReturn(false);
        when(auditService.recentByCategory(org, AuditCategory.SESSION)).thenReturn(List.of(entryWithPii()));

        AuditEntry shown = adminService.recentAudit(AuditCategory.SESSION, 0, 20).items().getFirst();

        assertThat(shown.actorEmail()).isNull();
        assertThat(shown.actorDisplay()).isNull();
        assertThat(shown.remoteIp()).isNull();
        assertThat(shown.actorId()).isNull();
        assertThat(shown.actorType()).isEqualTo(AuditActorType.USER); // coarse type retained
        assertThat(shown.principal()).isEqualTo("alice");
    }

    @Test
    void actorPiiIsRetainedWithTheGrant() {
        when(auditAccessPolicy.canRead(AuditCategory.SESSION)).thenReturn(true);
        unscoped();
        when(auditAccessPolicy.canReadPii()).thenReturn(true);
        when(auditService.recentByCategory(org, AuditCategory.SESSION)).thenReturn(List.of(entryWithPii()));

        AuditEntry shown = adminService.recentAudit(AuditCategory.SESSION, 0, 20).items().getFirst();

        assertThat(shown.actorEmail()).isEqualTo("alice@acme.test");
        assertThat(shown.actorDisplay()).isEqualTo("Alice A");
        assertThat(shown.remoteIp()).isEqualTo("203.0.113.1");
    }
}
