package com.example.sso.user.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.role.domain.UserRoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The users whose effective authority a deny on a given subject changes — so authoring or lifting it can
 * terminate their live sessions. A CONSERVATIVE over-approximation is safe: terminating a session the deny does
 * not actually affect just forces a harmless re-login. A role's holders are its direct + group-delegated members
 * (read as platform for the group side, RLS-blind, since the write happens off the members' tier). Org membership
 * is resolved from {@code app_user.org_id} directly (the user module owns the account and app_user has no RLS),
 * so the fan-out stays inside this module — depending on the organization module here would cycle.
 */
@Component
@RequiredArgsConstructor
class DenyAffectedUsers {

    private final UserRoleRepository userRoles;
    private final UserGroupRepository groups;
    private final AppUserRepository appUsers;
    private final OrgContext orgContext;

    /**
     * @param denyOrgId the org the deny row is stamped with — the scope of its EFFECT. A role deny is applied on
     *                  the read path only to holders whose own org matches it (or to all when it is a null-org
     *                  platform veto), so the fan-out is scoped the same way: a deny on a GLOBAL role must not
     *                  terminate another tenant's holders whose authority it never actually changes.
     */
    Set<UUID> forSubject(DenySubjectKind kind, UUID subjectId, UUID denyOrgId) {
        return switch (kind) {
            // Only an ORG deny legitimately carries a null subject (the platform veto); the other kinds always
            // name a concrete subject, so a null there is a defensive no-op rather than a whole-tenant sweep.
            case USER -> subjectId == null ? Set.of() : Set.of(subjectId);
            case GROUP -> subjectId == null ? Set.of() : new HashSet<>(groups.findMemberIdsByGroupIds(List.of(subjectId)));
            case ROLE -> subjectId == null ? Set.of() : roleHolders(subjectId, denyOrgId);
            case ORG -> orgMembers(subjectId);
        };
    }

    private Set<UUID> orgMembers(UUID orgId) {
        if (orgId != null) {
            return appUsers.findIdsByOrgId(orgId);
        }
        // A null-org ORG deny is the ABSOLUTE platform veto: it strips the pattern for every holder in every
        // tenant, so every live session must re-resolve. Leaving the strongest deny to take effect only on
        // expiry violates zero-trust; terminating a session the veto does not touch is a harmless re-login.
        return appUsers.findAllIds();
    }

    private Set<UUID> roleHolders(UUID roleId, UUID denyOrgId) {
        Set<UUID> ids = new HashSet<>(userRoles.findUserIdsByRoleId(roleId));
        ids.addAll(orgContext.callAsPlatform(() -> groups.findMemberIdsByRoleId(roleId)));
        if (denyOrgId != null) {
            ids.retainAll(appUsers.findIdsByOrgId(denyOrgId)); // a tenant's role deny reaches only its own holders
        }
        return ids; // a null-org platform veto (super) intentionally spans every holder
    }
}
