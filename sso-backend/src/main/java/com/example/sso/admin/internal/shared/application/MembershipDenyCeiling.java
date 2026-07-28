package com.example.sso.admin.internal.shared.application;

import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.group.UserGroupService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Dropping a membership is a LIFT act, and answers to the lift authority.
 *
 * <p>The grant model guards the {@code +} side: an administrator may hand out only what they already hold
 * ({@code mayGrantPermissions}), so self-granting is harmless and is deliberately allowed. A deny is the
 * {@code −} side, and removing one has its own authority ({@code DenyAuthority.mayLift}: authored it, or
 * strictly dominates the author's apex, plus the grant-symmetric ceiling on the pattern).
 *
 * <p>Taking somebody out of the role or group a deny rides on removes that subtraction without going near
 * either check. No grant is made — the permission was always granted, only the deny went — so the grant
 * ceiling never sees it, and a self-target rule would not either, since the same act works on an accomplice.
 * A deny resolves against the holder's APEX roles, which is why a group is asked about the roles it delegates
 * as well as about itself.
 *
 * <p>Deliberately at the ADMIN layer rather than in the membership services themselves: the mapping
 * re-evaluation drops memberships too, from a thread with no security context, where the lift authority fails
 * closed by design. Guarding the domain service would make every automatic retraction refuse.
 */
@Component
@RequiredArgsConstructor
public class MembershipDenyCeiling {

    private final DenyService denies;
    private final UserGroupService userGroups;

    /** Refuses the revocation when a deny on the role is one this administrator could not lift by hand. */
    public void requireMayDropRole(UUID roleId) {
        if (!denies.mayLiftEveryDenyOn(DenySubjectKind.ROLE, roleId)) {
            throw ForbiddenException.of("user.membership.denyGoverned");
        }
    }

    /** The same for a group, including every role the group delegates to its members. */
    public void requireMayDropGroup(UUID groupId) {
        if (!mayDropGroup(groupId)) {
            throw ForbiddenException.of("user.membership.denyGoverned");
        }
    }

    /** The verdict without the refusal, for a caller that reports rather than throws. */
    public boolean mayDropGroup(UUID groupId) {
        return denies.mayLiftEveryDenyOn(DenySubjectKind.GROUP, groupId)
                && userGroups.delegatedRoleIds(Set.of(groupId)).getOrDefault(groupId, Set.of()).stream()
                        .allMatch(roleId -> denies.mayLiftEveryDenyOn(DenySubjectKind.ROLE, roleId));
    }

    /** The verdict without the refusal, for a caller that reports rather than throws. */
    public boolean mayDropRole(UUID roleId) {
        return denies.mayLiftEveryDenyOn(DenySubjectKind.ROLE, roleId);
    }
}
