package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyAuthor;
import com.example.sso.user.deny.DenyAuthority;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.deny.LastAdminInvariant;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.OrgPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.UserPermissionDeny;
import com.example.sso.user.internal.rbac.domain.UserPermissionDenyRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deny service's ROUTING and tier-{@code org_id} derivation (the authorization itself lives behind the
 * {@link DenyAuthority} port and is tested in {@code AdminAccessPolicyTest}). Asserts each subject kind writes
 * the right table with the right org, and that a refused author/lift and an invalid pattern are rejected.
 */
@ExtendWith(MockitoExtension.class)
class DenyServiceImplTest {

    @Mock private DenyAuthority denyAuthority;
    @Mock private UserPermissionDenyRepository userDenies;
    @Mock private PrincipalPermissionDenyRepository principalDenies;
    @Mock private OrgPermissionDenyRepository orgDenies;
    @Mock private UserService users;
    @Mock private OrgContext orgContext;
    @Mock private DenyAffectedUsers affectedUsers;
    @Mock private AccessChangePublisher accessChanges;
    @Mock private LastAdminInvariant lastAdminInvariant;

    @InjectMocks private DenyServiceImpl service;

    private final UUID actorId = UUID.randomUUID();
    private final UUID apexId = UUID.randomUUID();

    @Test
    void aUserDenyIsStampedWithTheTargetUsersOwnOrg() {
        UUID userId = UUID.randomUUID();
        UUID userOrg = UUID.randomUUID();
        UUID denyId = UUID.randomUUID();
        authorize(DenySubjectKind.USER, userId, "user:read");
        UserAccount target = mock(UserAccount.class);
        when(target.getOrgId()).thenReturn(userOrg);
        when(users.findById(userId)).thenReturn(Optional.of(target));
        when(userDenies.findId(userId, "user:read")).thenReturn(Optional.of(denyId));

        assertThat(service.create(new DenySpec(DenySubjectKind.USER, userId, "user:read"))).isEqualTo(denyId);
        verify(userDenies).insertIfAbsent(userId, userOrg, "user:read", actorId, apexId);
    }

    @Test
    void aRoleDenyIsStampedWithTheActorsActingOrg() {
        UUID roleId = UUID.randomUUID();
        UUID actingOrg = UUID.randomUUID();
        authorize(DenySubjectKind.ROLE, roleId, "user:read");
        when(orgContext.currentOrg()).thenReturn(Optional.of(actingOrg));
        when(principalDenies.findId(DenySubjectType.ROLE, roleId, "user:read")).thenReturn(Optional.of(roleId));

        service.create(new DenySpec(DenySubjectKind.ROLE, roleId, "user:read"));
        verify(principalDenies).insertIfAbsent("ROLE", roleId, actingOrg, "user:read", actorId, apexId);
    }

    @Test
    void anOrgDenyIsStampedWithTheTargetOrgItself() {
        UUID orgId = UUID.randomUUID();
        authorize(DenySubjectKind.ORG, orgId, "user:read");
        when(orgDenies.findId(orgId, "user:read")).thenReturn(Optional.of(orgId));

        service.create(new DenySpec(DenySubjectKind.ORG, orgId, "user:read"));
        verify(orgDenies).insertIfAbsent(orgId, "user:read", actorId, apexId);
    }

    @Test
    void aNewUserDenyTerminatesTheTargetUsersSessions() {
        UUID userId = UUID.randomUUID();
        UUID userOrg = UUID.randomUUID();
        authorize(DenySubjectKind.USER, userId, "user:read");
        UserAccount target = mock(UserAccount.class);
        when(target.getOrgId()).thenReturn(userOrg);
        when(users.findById(userId)).thenReturn(Optional.of(target));
        when(userDenies.insertIfAbsent(userId, userOrg, "user:read", actorId, apexId)).thenReturn(1);
        when(userDenies.findId(userId, "user:read")).thenReturn(Optional.of(UUID.randomUUID()));
        when(affectedUsers.forSubject(DenySubjectKind.USER, userId, userOrg)).thenReturn(Set.of(userId));
        when(affectedUsers.tiersFor(DenySubjectKind.USER, userOrg, Set.of(userId))).thenReturn(Set.of(userOrg));

        service.create(new DenySpec(DenySubjectKind.USER, userId, "user:read"));
        // a new deny hands the guard its pattern and the tiers it reaches
        verify(lastAdminInvariant).ensureDenyRetainsAdmins("user:read", Set.of(userOrg));
        verify(accessChanges).forUserIds(Set.of(userId));
    }

    @Test
    void aPlatformWideRoleDenyForwardsTheReachedTiersToTheGuard() {
        // The gap this closes: an un-drilled super stamps a ROLE deny with org null (a platform veto). Recounting
        // the PLATFORM tier proves nothing — supers are deny-exempt, so it always passes while the veto can strip
        // the admin capability inside every tenant. The tiers recounted must be the ones the deny actually reaches.
        UUID roleId = UUID.randomUUID();
        UUID holderA = UUID.randomUUID();
        UUID holderB = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        authorize(DenySubjectKind.ROLE, roleId, "user:update");
        when(orgContext.currentOrg()).thenReturn(Optional.empty()); // un-drilled super: no acting org...
        when(orgContext.isPlatform()).thenReturn(true); // ...but genuinely on the platform tier
        when(principalDenies.insertIfAbsent("ROLE", roleId, null, "user:update", actorId, apexId)).thenReturn(1);
        when(principalDenies.findId(DenySubjectType.ROLE, roleId, "user:update"))
                .thenReturn(Optional.of(UUID.randomUUID()));
        when(affectedUsers.forSubject(DenySubjectKind.ROLE, roleId, null)).thenReturn(Set.of(holderA, holderB));
        when(affectedUsers.tiersFor(DenySubjectKind.ROLE, null, Set.of(holderA, holderB)))
                .thenReturn(Set.of(orgA, orgB));

        service.create(new DenySpec(DenySubjectKind.ROLE, roleId, "user:update"));

        verify(lastAdminInvariant).ensureDenyRetainsAdmins("user:update", Set.of(orgA, orgB));
    }

    @Test
    void aRoleDenyFromAnUnboundNonPlatformContextIsRefusedRatherThanStampedAsAPlatformVeto() {
        // currentOrg() is empty for the platform tier AND for a fully authenticated principal carrying no org
        // marker. Stamping the latter's deny null would silently make it apply in EVERY tenant, so the platform
        // veto must be an asserted capability rather than the fall-through value of an unbound context.
        UUID roleId = UUID.randomUUID();
        authorize(DenySubjectKind.ROLE, roleId, "user:read");
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service.create(new DenySpec(DenySubjectKind.ROLE, roleId, "user:read")))
                .isInstanceOf(ForbiddenException.class);
        verify(principalDenies, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aNewDenyThatWouldStripTheTiersLastAdminIsRefusedBeforeAnySessionTermination() {
        UUID userId = UUID.randomUUID();
        UUID userOrg = UUID.randomUUID();
        authorize(DenySubjectKind.USER, userId, "user:update");
        UserAccount target = mock(UserAccount.class);
        when(target.getOrgId()).thenReturn(userOrg);
        when(users.findById(userId)).thenReturn(Optional.of(target));
        when(userDenies.insertIfAbsent(userId, userOrg, "user:update", actorId, apexId)).thenReturn(1);
        when(userDenies.findId(userId, "user:update")).thenReturn(Optional.of(UUID.randomUUID()));
        when(affectedUsers.forSubject(DenySubjectKind.USER, userId, userOrg)).thenReturn(Set.of(userId));
        when(affectedUsers.tiersFor(DenySubjectKind.USER, userOrg, Set.of(userId))).thenReturn(Set.of(userOrg));
        doThrow(new ConflictException("admin.lastAdmin"))
                .when(lastAdminInvariant).ensureDenyRetainsAdmins("user:update", Set.of(userOrg));

        assertThatThrownBy(() -> service.create(new DenySpec(DenySubjectKind.USER, userId, "user:update")))
                .isInstanceOf(ConflictException.class);
        verify(accessChanges, never()).forUserIds(any()); // the guard runs BEFORE termination — no side effects
    }

    @Test
    void anIdempotentReCreateChangesNothingSoTerminatesNoOne() {
        UUID userId = UUID.randomUUID();
        UUID userOrg = UUID.randomUUID();
        authorize(DenySubjectKind.USER, userId, "user:read");
        UserAccount target = mock(UserAccount.class);
        when(target.getOrgId()).thenReturn(userOrg);
        when(users.findById(userId)).thenReturn(Optional.of(target));
        when(userDenies.insertIfAbsent(userId, userOrg, "user:read", actorId, apexId)).thenReturn(0); // ON CONFLICT
        when(userDenies.findId(userId, "user:read")).thenReturn(Optional.of(UUID.randomUUID()));

        service.create(new DenySpec(DenySubjectKind.USER, userId, "user:read"));
        verify(accessChanges, never()).forUserIds(any());
        verify(lastAdminInvariant, never()).ensureDenyRetainsAdmins(any(), any()); // a no-op re-create recounts nothing
    }

    @Test
    void liftingAnUnknownDenyIdIsASilentNoOp() {
        UUID denyId = UUID.randomUUID();
        when(userDenies.findById(denyId)).thenReturn(Optional.empty());

        service.lift(denyId, DenySubjectKind.USER);

        verify(userDenies, never()).deleteById(any());
        verify(accessChanges, never()).forUserIds(any());
    }

    @Test
    void liftingAnUnknownOrgDenyIdIsASilentNoOp() {
        UUID denyId = UUID.randomUUID();
        when(orgDenies.findById(denyId)).thenReturn(Optional.empty());

        service.lift(denyId, DenySubjectKind.ORG);

        verify(orgDenies, never()).deleteById(any());
        verify(accessChanges, never()).forUserIds(any());
    }

    @Test
    void liftingAUserDenyTerminatesTheTargetUsersSessions() {
        UUID denyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID userOrg = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        UUID writerApex = UUID.randomUUID();
        UserPermissionDeny deny = mock(UserPermissionDeny.class);
        when(deny.getUserId()).thenReturn(userId);
        when(deny.getOrgId()).thenReturn(userOrg);
        when(deny.getPattern()).thenReturn("user:read");
        when(deny.getCreatedBy()).thenReturn(createdBy);
        when(deny.getWriterApexRoleId()).thenReturn(writerApex);
        when(userDenies.findById(denyId)).thenReturn(Optional.of(deny));
        when(denyAuthority.mayLift(DenySubjectKind.USER, userId, "user:read", createdBy, writerApex)).thenReturn(true);
        when(affectedUsers.forSubject(DenySubjectKind.USER, userId, userOrg)).thenReturn(Set.of(userId));

        service.lift(denyId, DenySubjectKind.USER);
        verify(userDenies).deleteById(denyId);
        verify(accessChanges).forUserIds(Set.of(userId));
        verify(lastAdminInvariant, never()).ensureDenyRetainsAdmins(any(), any()); // lifting widens access, never bricks
    }

    @Test
    void aRefusedLiftNeitherDeletesNorTerminates() {
        UUID denyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserPermissionDeny deny = mock(UserPermissionDeny.class);
        when(deny.getUserId()).thenReturn(userId);
        when(deny.getPattern()).thenReturn("user:read");
        when(deny.getCreatedBy()).thenReturn(UUID.randomUUID());
        when(deny.getWriterApexRoleId()).thenReturn(UUID.randomUUID());
        when(userDenies.findById(denyId)).thenReturn(Optional.of(deny));
        when(denyAuthority.mayLift(any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.lift(denyId, DenySubjectKind.USER))
                .isInstanceOf(ForbiddenException.class);
        verify(userDenies, never()).deleteById(any());
        verify(accessChanges, never()).forUserIds(any());
    }

    @Test
    void aRefusedAuthorIsForbidden() {
        UUID userId = UUID.randomUUID();
        when(denyAuthority.authorizeAuthor(DenySubjectKind.USER, userId, "user:read")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new DenySpec(DenySubjectKind.USER, userId, "user:read")))
                .isInstanceOf(ForbiddenException.class);
        verify(userDenies, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void anInvalidPatternIsRejectedBeforeAuthorization() {
        assertThatThrownBy(() -> service.create(new DenySpec(DenySubjectKind.USER, UUID.randomUUID(), "bogus")))
                .isInstanceOf(BadRequestException.class);
        verify(denyAuthority, never()).authorizeAuthor(any(), any(), any());
    }

    private void authorize(DenySubjectKind kind, UUID subjectId, String pattern) {
        when(denyAuthority.authorizeAuthor(kind, subjectId, pattern))
                .thenReturn(Optional.of(new DenyAuthor(actorId, apexId)));
    }
}
