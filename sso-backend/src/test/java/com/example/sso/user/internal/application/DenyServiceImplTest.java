package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyAuthor;
import com.example.sso.user.deny.DenyAuthority;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.OrgPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.UserPermissionDenyRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
