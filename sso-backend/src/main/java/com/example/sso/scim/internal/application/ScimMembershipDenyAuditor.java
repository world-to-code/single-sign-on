package com.example.sso.scim.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.scim.ScimBearerTokenFilter;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.deny.ScopedDenyRow;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Records that a SCIM sync dropped the membership a deny resolved against — the one route where that act is
 * allowed to happen unchecked, so it must at least be visible.
 *
 * <p>A deny resolves against the holder's apex roles, so taking somebody out of the denied role removes the
 * subtraction without a grant being made anywhere. The console answers that with a ceiling: the actor must be
 * able to lift the deny by hand ({@code MembershipDenyCeiling}). SCIM cannot be held to it — its principal is
 * a token, not a person, so the lift authority is unresolvable and the ceiling would refuse every affected
 * sync forever, with nothing any operator could do to satisfy it.
 *
 * <p>Refusing would also answer the wrong question. The directory is the record of who belongs to what; an IdP
 * that keeps a membership the directory has dropped is asserting something it does not own, and stale
 * membership is its own security problem. So the sync proceeds and leaves the trail the deny's author can act
 * on — re-aim the deny at the user, or at the role the permission actually comes from.
 */
@Component
@RequiredArgsConstructor
class ScimMembershipDenyAuditor {

    /** Stands in for the platform tier while grouping, since a map key cannot be null. */
    private static final UUID PLATFORM = new UUID(0L, 0L);

    private final DenyService denies;
    private final AuditService audit;

    /**
     * Notes the denies {@code lostMembers} stop being subject to by losing {@code roleId}. Silent when nothing
     * rode on the role or nothing was lost, which is the overwhelmingly common case — a deny is rare and this
     * runs on every group write.
     *
     * <p>Call AFTER the write, with the lost members read BEFORE it: the row must not assert a lift that a
     * later refusal undoes (deleting a system role is refused outright, and the audit write commits in its own
     * transaction), and after the write the members are simply absent so there is nothing left to read.
     *
     * <p>ONE row per tier, stamped with the deny's own tier rather than the acting one. The person who can act
     * on this — re-aim the deny at the user, or at the role the permission actually comes from — is whoever
     * authored it, and the audit read path is tier-scoped: a row stamped platform is invisible to them.
     */
    void noteLiftedBy(String cause, UUID roleId, String roleName, Collection<UUID> lostMembers) {
        if (lostMembers.isEmpty()) {
            return; // nothing left the role, so nothing was lifted — and no reason to go looking
        }
        Map<UUID, List<String>> liftedByTier = denies.principalDeniesAcrossTiers(DenySubjectKind.ROLE, roleId)
                .stream()
                .collect(Collectors.groupingBy(row -> Optional.ofNullable(row.orgId()).orElse(PLATFORM),
                        Collectors.mapping(ScopedDenyRow::pattern, Collectors.toList())));
        liftedByTier.forEach((tier, patterns) -> record(cause, roleId, roleName, lostMembers.size(), tier, patterns));
    }

    private void record(String cause, UUID roleId, String roleName, int lost, UUID tier, List<String> patterns) {
        String detail = "%s: %d member(s) lost role %s (%s), lifting deny %s"
                .formatted(cause, lost, roleId, roleName, patterns);
        audit.record(new AuditRecord(AuditType.PERMISSION_DENY_LIFTED_BY_SYNC, ScimBearerTokenFilter.SCIM_PRINCIPAL, true, detail,
                null, AuditSubjectType.NONE, roleId.toString(), PLATFORM.equals(tier) ? null : tier));
    }
}
