package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.user.application.UserDetailAdminService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.group.UserGroupService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GroupAdminService}. Focus: every by-id operation is gated by
 * {@link AdminAccessPolicy#canAccessGroup} and must NOT delegate to {@link UserGroupService} when the
 * group is outside the actor's scope (403), {@code list} filters to scoped groups, and the delegation
 * mutations are audited (verify interactions).
 */
class GroupAdminServiceTest {

    private static final UUID GROUP_ID = UUID.randomUUID();

    private UserGroupService userGroups;
    private ApplicationService applications;
    private AdminAccessPolicy accessPolicy;
    private AdminAuditLogger auditLogger;
    private UserDetailAdminService userDetail;
    private OrgContext orgContext;
    private GroupAdminService service;

    @BeforeEach
    void setUp() {
        userGroups = mock(UserGroupService.class);
        applications = mock(ApplicationService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        userDetail = mock(UserDetailAdminService.class);
        orgContext = mock(OrgContext.class);
        service = new GroupAdminService(userGroups, applications, accessPolicy, auditLogger, userDetail, orgContext);
    }

    @Test
    void getOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.get(GROUP_ID)).isInstanceOf(ForbiddenException.class);
        verify(userGroups, never()).get(any());
    }

    @Test
    void updateOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.update(GROUP_ID, null)).isInstanceOf(ForbiddenException.class);
        verify(userGroups, never()).update(any(), any());
    }

    @Test
    void deleteOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(GROUP_ID)).isInstanceOf(ForbiddenException.class);
        verify(userGroups, never()).delete(any());
    }

    @Test
    void setRolesOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.setRoles(GROUP_ID, Set.of("ROLE_SUPPORT")))
                .isInstanceOf(ForbiddenException.class);
        verify(userGroups, never()).setRoles(any(), any());
    }

    @Test
    void getInScopeDelegatesToUserGroupService() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(true);
        GroupView view = group(GROUP_ID);
        when(userGroups.get(GROUP_ID)).thenReturn(view);

        assertThat(service.get(GROUP_ID)).isSameAs(view);
    }

    @Test
    void setRolesInScopeDelegatesAndAudits() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(true);
        when(userGroups.setRoles(eq(GROUP_ID), any())).thenReturn(group(GROUP_ID));

        service.setRoles(GROUP_ID, Set.of("ROLE_SUPPORT"));

        verify(userGroups).setRoles(GROUP_ID, Set.of("ROLE_SUPPORT"));
        verify(auditLogger).log(eq(AuditType.GROUP_ROLES_UPDATED), eq(AuditSubjectType.GROUP), any(), any());
    }

    @Test
    void listPagesOnlyTheScopedGroupIdsInTheDatabase() {
        UUID scopedId = UUID.randomUUID();
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentScopedGroupIds()).thenReturn(Set.of(scopedId));
        when(userGroups.listByIds(Set.of(scopedId), 0, 100))
                .thenReturn(new Page<>(1, 0, 100, List.of(group(scopedId))));

        assertThat(service.list(0, 100).items()).extracting(GroupView::id).containsExactly(scopedId.toString());
    }

    @Test
    void aTenantAdminListsOnlyTheirOwnOrgsGroupsNotTheGlobalOnes() {
        // The reported leak: a tenant admin's Groups page showed a SECOND "All Users" — the GLOBAL system group
        // RLS keeps visible. The list must query ONLY the acting org's groups (listByOrg), never listAll.
        UUID org = UUID.randomUUID();
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.administersBoundOrg()).thenReturn(true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(org));
        when(userGroups.listByOrg(org, 0, 100)).thenReturn(new Page<>(1, 0, 100, List.of(group(UUID.randomUUID()))));

        assertThat(service.list(0, 100).items()).hasSize(1);
        verify(userGroups).listByOrg(org, 0, 100);
        verify(userGroups, never()).listAll(anyInt(), anyInt()); // never the RLS-wide (globals-included) list
    }

    @Test
    void anUnDrilledPlatformAdminSeesOnlyGlobalGroupsNotEveryTenantsMerged() {
        // Drill-in scoping: a super-admin who has NOT drilled into a tenant (tier null) sees only the global
        // groups — they must drill into a tenant to see its groups, never all tenants merged.
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(orgContext.currentOrg()).thenReturn(Optional.empty()); // not drilled
        when(userGroups.listByOrg(null, 0, 100))
                .thenReturn(new Page<>(1, 0, 100, List.of(group(UUID.randomUUID()))));

        assertThat(service.list(0, 100).items()).hasSize(1);
        verify(userGroups, never()).listAll(anyInt(), anyInt()); // never the RLS-wide (all-tenant) list
        verify(accessPolicy, never()).currentScopedGroupIds();
    }

    @Test
    void terminatingSessionsOutsideScopeIsForbiddenAndDoesNotEnumerateMembers() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.terminateMemberSessions(GROUP_ID)).isInstanceOf(ForbiddenException.class);
        verify(userGroups, never()).memberIdsOf(any());
        verify(userDetail, never()).terminateSessions(any());
    }

    @Test
    void terminatesEveryReachableRevocableMemberAndSkipsTheOthers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID outOfScope = UUID.randomUUID();
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(true);
        when(userGroups.memberIdsOf(List.of(GROUP_ID)))
                .thenReturn(new LinkedHashSet<>(List.of(a, b, admin, outOfScope)));
        // a, b: in scope + revocable. admin: in scope but not revocable. outOfScope: not reachable by the caller.
        when(accessPolicy.canAccessUser(a)).thenReturn(true);
        when(accessPolicy.canRevokeSessions(a)).thenReturn(true);
        when(accessPolicy.canAccessUser(b)).thenReturn(true);
        when(accessPolicy.canRevokeSessions(b)).thenReturn(true);
        when(accessPolicy.canAccessUser(admin)).thenReturn(true);
        when(accessPolicy.canRevokeSessions(admin)).thenReturn(false); // a scoped delegate can't log out an admin
        when(accessPolicy.canAccessUser(outOfScope)).thenReturn(false); // outside the delegate's subtree
        when(userDetail.terminateSessions(a)).thenReturn(2);
        when(userDetail.terminateSessions(b)).thenReturn(3);

        GroupSessionTermination result = service.terminateMemberSessions(GROUP_ID);

        assertThat(result).isEqualTo(new GroupSessionTermination(2, 5, 2)); // skipped = admin + out-of-scope
        verify(userDetail, never()).terminateSessions(admin);      // never escalate to an admin
        verify(userDetail, never()).terminateSessions(outOfScope); // never reach outside the subtree
        verify(auditLogger).log(eq(AuditType.SESSION_ADMIN_REVOKED), eq(AuditSubjectType.GROUP),
                eq(GROUP_ID.toString()),
                argThat(d -> d.contains("users=2") && d.contains("sessions=5") && d.contains("skipped=2")));
    }

    @Test
    void aMemberThatVanishesMidBatchIsSkippedAndTheRestStillTerminated() {
        UUID a = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(true);
        when(userGroups.memberIdsOf(List.of(GROUP_ID))).thenReturn(new LinkedHashSet<>(List.of(a, gone, b)));
        for (UUID m : List.of(a, gone, b)) {
            when(accessPolicy.canAccessUser(m)).thenReturn(true);
            when(accessPolicy.canRevokeSessions(m)).thenReturn(true);
        }
        when(userDetail.terminateSessions(a)).thenReturn(1);
        when(userDetail.terminateSessions(gone)).thenThrow(new NotFoundException("user vanished"));
        when(userDetail.terminateSessions(b)).thenReturn(1);

        GroupSessionTermination result = service.terminateMemberSessions(GROUP_ID);

        assertThat(result).isEqualTo(new GroupSessionTermination(2, 2, 1)); // a+b done, gone skipped, not aborted
        verify(userDetail).terminateSessions(b); // the member AFTER the failure is still processed
        verify(auditLogger).log(eq(AuditType.SESSION_ADMIN_REVOKED), any(), any(), any()); // trail still written
    }

    @Test
    void anEmptyGroupTerminatesNothingButStillAudits() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(true);
        when(userGroups.memberIdsOf(List.of(GROUP_ID))).thenReturn(Set.of());

        assertThat(service.terminateMemberSessions(GROUP_ID)).isEqualTo(new GroupSessionTermination(0, 0, 0));
        verify(userDetail, never()).terminateSessions(any());
        verify(auditLogger).log(eq(AuditType.SESSION_ADMIN_REVOKED), eq(AuditSubjectType.GROUP),
                eq(GROUP_ID.toString()), argThat(d -> d.contains("users=0")));
    }

    private GroupView group(UUID id) {
        return new GroupView(id.toString(), "g-" + id, null, null, List.of(), 0, false, List.of());
    }
}
