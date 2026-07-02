package com.example.sso.admin.internal.group.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.GroupView;
import com.example.sso.user.UserGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private GroupAdminService service;

    @BeforeEach
    void setUp() {
        userGroups = mock(UserGroupService.class);
        applications = mock(ApplicationService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        service = new GroupAdminService(userGroups, applications, accessPolicy, auditLogger);
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
    void setManagersOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessGroup(GROUP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.setManagers(GROUP_ID, List.of()))
                .isInstanceOf(ForbiddenException.class);
        verify(userGroups, never()).setManagers(any(), any());
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
    void listFiltersToScopedGroupsForAScopedActor() {
        UUID scopedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentScopedGroupIds()).thenReturn(Set.of(scopedId));
        when(userGroups.listAll()).thenReturn(List.of(group(scopedId), group(otherId)));

        assertThat(service.list()).extracting(GroupView::id).containsExactly(scopedId.toString());
    }

    @Test
    void listReturnsEverythingForAnUnscopedActor() {
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(userGroups.listAll()).thenReturn(List.of(group(UUID.randomUUID()), group(UUID.randomUUID())));

        assertThat(service.list()).hasSize(2);
        verify(accessPolicy, never()).currentScopedGroupIds();
    }

    private GroupView group(UUID id) {
        return new GroupView(id.toString(), "g-" + id, null, null, List.of(), 0, false, List.of(), List.of());
    }
}
