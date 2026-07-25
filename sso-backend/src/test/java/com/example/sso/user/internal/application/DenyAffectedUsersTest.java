package com.example.sso.user.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.role.domain.UserRoleRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The affected-user fan-out per deny subject kind — the set whose sessions a deny write terminates. The two
 * cases that matter for isolation: a ROLE deny is scoped to the deny's own org (so a deny on a GLOBAL role
 * cannot terminate another tenant's holders), and a null-org ORG deny is the platform veto that spans every
 * tenant's members.
 */
@ExtendWith(MockitoExtension.class)
class DenyAffectedUsersTest {

    @Mock private UserRoleRepository userRoles;
    @Mock private UserGroupRepository groups;
    @Mock private AppUserRepository appUsers;
    @Mock private OrgContext orgContext;

    @InjectMocks private DenyAffectedUsers affectedUsers;

    private final UUID roleId = UUID.randomUUID();
    private final UUID orgA = UUID.randomUUID();

    @Test
    void aUserDenyAffectsOnlyThatUser() {
        UUID userId = UUID.randomUUID();
        assertThat(affectedUsers.forSubject(DenySubjectKind.USER, userId, orgA)).containsExactly(userId);
    }

    @Test
    void aGroupDenyAffectsTheGroupMembers() {
        UUID groupId = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        when(groups.findMemberIdsByGroupIds(List.of(groupId))).thenReturn(List.of(member));

        assertThat(affectedUsers.forSubject(DenySubjectKind.GROUP, groupId, orgA)).containsExactly(member);
    }

    @Test
    void anOrgScopedRoleDenyIsConfinedToTheDenysOwnOrgHolders() {
        UUID orgAHolder = UUID.randomUUID();
        UUID orgBHolder = UUID.randomUUID(); // holds the same GLOBAL role in another tenant
        runPlatformSupplierInline();
        when(userRoles.findUserIdsByRoleId(roleId)).thenReturn(List.of(orgAHolder));
        when(groups.findMemberIdsByRoleId(roleId)).thenReturn(List.of(orgBHolder));
        when(appUsers.findIdsByOrgId(orgA)).thenReturn(Set.of(orgAHolder)); // only org-A members survive

        Set<UUID> affected = affectedUsers.forSubject(DenySubjectKind.ROLE, roleId, orgA);

        assertThat(affected).containsExactly(orgAHolder);
        assertThat(affected).doesNotContain(orgBHolder); // the other tenant's holder is NOT terminated
    }

    @Test
    void aPlatformRoleVetoWithNoOrgSpansEveryHolder() {
        UUID orgAHolder = UUID.randomUUID();
        UUID orgBHolder = UUID.randomUUID();
        runPlatformSupplierInline();
        when(userRoles.findUserIdsByRoleId(roleId)).thenReturn(List.of(orgAHolder));
        when(groups.findMemberIdsByRoleId(roleId)).thenReturn(List.of(orgBHolder));

        Set<UUID> affected = affectedUsers.forSubject(DenySubjectKind.ROLE, roleId, null);

        assertThat(affected).containsExactlyInAnyOrder(orgAHolder, orgBHolder);
        verify(appUsers, never()).findIdsByOrgId(any()); // no org intersection when the veto is platform-wide
    }

    @Test
    void anOrgScopedOrgDenyAffectsThatOrgsMembers() {
        UUID member = UUID.randomUUID();
        when(appUsers.findIdsByOrgId(orgA)).thenReturn(Set.of(member));

        assertThat(affectedUsers.forSubject(DenySubjectKind.ORG, orgA, orgA)).containsExactly(member);
    }

    @Test
    void aNullOrgPlatformVetoAffectsEveryUserAcrossTenants() {
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        when(appUsers.findAllIds()).thenReturn(Set.of(memberA, memberB));

        assertThat(affectedUsers.forSubject(DenySubjectKind.ORG, null, null))
                .containsExactlyInAnyOrder(memberA, memberB);
    }

    private void runPlatformSupplierInline() {
        lenient().when(orgContext.callAsPlatform(any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(0).get());
    }
}
