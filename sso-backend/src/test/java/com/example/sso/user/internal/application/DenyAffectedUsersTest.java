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
        when(userRoles.findAllAssignedUserIds(roleId)).thenReturn(List.of(orgAHolder));
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
        when(userRoles.findAllAssignedUserIds(roleId)).thenReturn(List.of(orgAHolder));
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

    // --- tiersFor: the TIERS a deny can brick, which is not always the tier it is stamped with -------

    @Test
    void aStampedOrgIsItsOwnReach() {
        // Every actor the console offers is org-bound, so the stamp IS the reach — and deriving it must not
        // cost a query.
        assertThat(affectedUsers.tiersFor(DenySubjectKind.ROLE, orgA, Set.of(UUID.randomUUID())))
                .containsExactly(orgA);
        verify(appUsers, never()).findDistinctOrgIdsByIds(any());
        verify(appUsers, never()).findDistinctOrgIds();
    }

    @Test
    void theAbsolutePlatformVetoReachesEveryTierThatHasAUser() {
        // A null-org ORG deny strips the pattern from every user in every tenant (it beats even a USER allow),
        // so every tier must be recounted. Deriving that from the whole-table fan-out would be a table-wide IN.
        UUID orgB = UUID.randomUUID();
        when(appUsers.findDistinctOrgIds()).thenReturn(Set.of(orgA, orgB));

        assertThat(affectedUsers.tiersFor(DenySubjectKind.ORG, null, Set.of(UUID.randomUUID())))
                .containsExactlyInAnyOrder(orgA, orgB);
        verify(appUsers, never()).findDistinctOrgIdsByIds(any());
    }

    @Test
    void aNullOrgRoleVetoReachesTheTiersItsHoldersLiveIn() {
        UUID holderA = UUID.randomUUID();
        UUID holderB = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        when(appUsers.findDistinctOrgIdsByIds(Set.of(holderA, holderB))).thenReturn(Set.of(orgA, orgB));

        assertThat(affectedUsers.tiersFor(DenySubjectKind.ROLE, null, Set.of(holderA, holderB)))
                .containsExactlyInAnyOrder(orgA, orgB);
    }

    @Test
    void aGlobalUsersTierIsThePlatformTier() {
        // app_user.org_id is null for a global user, so the null element must survive as "the platform tier"
        // rather than being dropped — the guard reads null as the tier to recount.
        UUID globalUser = UUID.randomUUID();
        Set<UUID> platformOnly = new HashSet<>();
        platformOnly.add(null);
        when(appUsers.findDistinctOrgIdsByIds(Set.of(globalUser))).thenReturn(platformOnly);

        assertThat(affectedUsers.tiersFor(DenySubjectKind.USER, null, Set.of(globalUser)))
                .containsExactly((UUID) null);
    }

    @Test
    void anEmptyFanOutReachesNoTierAndCostsNoQuery() {
        assertThat(affectedUsers.tiersFor(DenySubjectKind.ROLE, null, Set.of())).isEmpty();
        verify(appUsers, never()).findDistinctOrgIdsByIds(any());
    }

    private void runPlatformSupplierInline() {
        lenient().when(orgContext.callAsPlatform(any())).thenAnswer(invocation ->
                invocation.<Supplier<?>>getArgument(0).get());
    }
}
