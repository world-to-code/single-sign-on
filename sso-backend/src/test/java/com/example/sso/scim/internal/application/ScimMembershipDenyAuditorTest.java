package com.example.sso.scim.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.deny.ScopedDenyRow;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SCIM half of the membership-deny story. The console refuses a drop the actor could not lift by hand;
 * SCIM has no person to ask, so it proceeds and must leave a trail instead — a trail that is worthless if it
 * fires on every sync, and worse than worthless if it stays silent on the one that matters.
 */
@ExtendWith(MockitoExtension.class)
class ScimMembershipDenyAuditorTest {

    private static final UUID ROLE = UUID.randomUUID();

    @Mock private DenyService denies;
    @Mock private AuditService audit;

    @InjectMocks private ScimMembershipDenyAuditor auditor;

    @Test
    void recordsTheLiftWhenMembersLoseARoleThatCarriesADeny() {
        when(denies.principalDeniesAcrossTiers(DenySubjectKind.ROLE, ROLE))
                .thenReturn(List.of(new ScopedDenyRow(UUID.randomUUID(), "org:create", null)));

        auditor.noteLiftedBy("SCIM group update", ROLE, "engineers", Set.of(UUID.randomUUID()));

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(recorded.capture());
        assertThat(recorded.getValue().type()).isEqualTo(AuditType.PERMISSION_DENY_LIFTED_BY_SYNC);
        // The pattern that came back is the whole point: without it the row says a lift happened but not of what.
        assertThat(recorded.getValue().detail()).contains("org:create").contains("engineers");
        assertThat(recorded.getValue().orgId()).isNull(); // a platform veto belongs to the platform tier
    }

    /**
     * One row PER TIER, each stamped with the deny's own org rather than the acting one. The audit read path
     * is tier-scoped, and the only person who can act on this — re-aim the deny at the user, or at the role the
     * permission actually comes from — is whoever authored it. A row stamped platform is invisible to them.
     */
    @Test
    void recordsOneRowPerTierStampedWithTheDenysOwnOrg() {
        UUID tenant = UUID.randomUUID();
        when(denies.principalDeniesAcrossTiers(DenySubjectKind.ROLE, ROLE)).thenReturn(List.of(
                new ScopedDenyRow(UUID.randomUUID(), "org:create", null),
                new ScopedDenyRow(UUID.randomUUID(), "user:delete", tenant)));

        auditor.noteLiftedBy("SCIM group update", ROLE, "engineers", Set.of(UUID.randomUUID()));

        ArgumentCaptor<AuditRecord> recorded = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit, times(2)).record(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(AuditRecord::orgId)
                .containsExactlyInAnyOrder(null, tenant);
    }

    /**
     * And the read is tier-AGNOSTIC on purpose. SCIM group management runs with no org context bound, where the
     * deny RLS policy is fail-closed — so the tier-scoped {@code principalDenies} would return only the
     * platform veto and report "nothing rides on this role", silently. This control would have been a no-op.
     */
    @Test
    void asksForDeniesAcrossTiersNotJustTheActingOne() {
        when(denies.principalDeniesAcrossTiers(DenySubjectKind.ROLE, ROLE)).thenReturn(List.of());

        auditor.noteLiftedBy("SCIM group update", ROLE, "engineers", Set.of(UUID.randomUUID()));

        verify(denies, never()).principalDenies(any(), any());
    }

    @Test
    void staysSilentWhenTheSyncLosesNobody() {
        auditor.noteLiftedBy("SCIM group update", ROLE, "engineers", Set.of());

        verify(audit, never()).record(any());
        verify(denies, never()).principalDeniesAcrossTiers(any(), any()); // nor pays for the lookup
    }

    @Test
    void staysSilentWhenNoDenyRodeOnTheRole() {
        // The overwhelmingly common case. A row here on every membership change would bury the real ones.
        when(denies.principalDeniesAcrossTiers(DenySubjectKind.ROLE, ROLE)).thenReturn(List.of());

        auditor.noteLiftedBy("SCIM group update", ROLE, "engineers", Set.of(UUID.randomUUID()));

        verify(audit, never()).record(any());
    }
}
