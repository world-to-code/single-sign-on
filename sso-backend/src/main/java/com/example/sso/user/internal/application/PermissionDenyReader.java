package com.example.sso.user.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.OrgPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.UserPermissionDenyRepository;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a user's applicable deny rows AS PLATFORM, so a deny evaluated OUTSIDE the row's tier (a login callback,
 * an off-request re-check) still applies even though the caller's RLS scope cannot see it — an RLS blind spot
 * must never become a deny bypass. The reads are keyed strictly by subject id / org, so the platform read
 * returns ONLY the rows targeting this user, their roles/groups, and their org, plus the platform veto.
 */
@Component
@RequiredArgsConstructor
class PermissionDenyReader {

    private final UserPermissionDenyRepository userDenies;
    private final PrincipalPermissionDenyRepository principalDenies;
    private final OrgPermissionDenyRepository orgDenies;
    private final OrgContext orgContext;

    /**
     * {@code apexRoleIds} are the user's TOP roles only — passing the apex (not every held role) IS the ROLE-
     * subject deny carve-out: a deny on a role dominated by a higher held role is never read, so a subordinate's
     * deny cannot cut a superior. GROUP denies use every membership (groups are not dominated).
     */
    DenyRows read(UUID userId, Set<UUID> apexRoleIds, Set<UUID> groupIds, UUID orgId) {
        return orgContext.callAsPlatform(() -> new DenyRows(
                userDenies.findPatternsByUser(userId),
                patternsFor(DenySubjectType.ROLE, apexRoleIds, orgId),
                patternsFor(DenySubjectType.GROUP, groupIds, orgId),
                orgId == null ? Set.of() : orgDenies.findPatternsByOrg(orgId),
                orgDenies.findPlatformPatterns()));
    }

    /**
     * Principal denies on {@code subjectIds}, scoped to the resolving user's {@code orgId} so a tenant's deny on
     * a globally-visible role id cannot subtract from another tenant's holders (a platform veto, {@code org_id}
     * NULL, still applies). An empty id set would make {@code in ()} error or match nothing — skip the query.
     */
    private Set<String> patternsFor(DenySubjectType subjectType, Collection<UUID> subjectIds, UUID orgId) {
        return subjectIds.isEmpty() ? Set.of() : principalDenies.findPatterns(subjectType, subjectIds, orgId);
    }
}
