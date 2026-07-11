package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.access.AppAccessQuery;
import com.example.sso.portal.access.AppAssignmentView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.access.AssignAppRequest;
import com.example.sso.user.account.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationServiceImpl}: it is a thin facade, so each method must forward to the
 * right collaborator (catalog / assignments / access) and pass its result through unchanged. The
 * behaviour of the collaborators is covered by {@code AppCatalogTest}, {@code AppAssignmentManagerTest},
 * and {@code AppAccessResolverTest}.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTest {

    @Mock private AppCatalog catalog;
    @Mock private AppAssignmentManager assignments;
    @Mock private AppAccessResolver access;
    @InjectMocks private ApplicationServiceImpl service;

    @Test
    void listApplicationsDelegatesToTheCatalog() {
        List<ApplicationView> apps = List.of();
        when(catalog.list()).thenReturn(apps);

        assertThat(service.listApplications()).isSameAs(apps);
        verify(catalog).list();
    }

    @Test
    void appAccessDelegatesToTheResolver() {
        AppAccessQuery query = new AppAccessQuery(mock(UserAccount.class), AppType.OIDC, "app", Set.of(), null);
        AppAccess result = new AppAccess(true, List.of());
        when(access.appAccess(query)).thenReturn(result);

        assertThat(service.appAccess(query)).isSameAs(result);
        verify(access).appAccess(query);
    }

    @Test
    void setAppPolicyDelegatesToTheResolver() {
        service.setAppPolicy(AppType.OIDC, "app", "policy");

        verify(access).setAppPolicy(AppType.OIDC, "app", "policy");
    }

    @Test
    void appsForUserDelegatesToAssignments() {
        UserAccount user = mock(UserAccount.class);
        List<ApplicationView> apps = List.of();
        when(assignments.appsForUser(user)).thenReturn(apps);

        assertThat(service.appsForUser(user)).isSameAs(apps);
        verify(assignments).appsForUser(user);
    }

    @Test
    void hasAssignmentDelegatesToAssignments() {
        UserAccount user = mock(UserAccount.class);
        when(assignments.hasAssignment(user, AppType.OIDC, "app")).thenReturn(true);

        assertThat(service.hasAssignment(user, AppType.OIDC, "app")).isTrue();
        verify(assignments).hasAssignment(user, AppType.OIDC, "app");
    }

    @Test
    void appsForGroupDelegatesToAssignments() {
        UUID groupId = UUID.randomUUID();
        List<ApplicationView> apps = List.of();
        when(assignments.appsForGroup(groupId)).thenReturn(apps);

        assertThat(service.appsForGroup(groupId)).isSameAs(apps);
        verify(assignments).appsForGroup(groupId);
    }

    @Test
    void assignmentsForAppDelegatesToAssignments() {
        List<AppAssignmentView> views = List.of();
        when(assignments.assignmentsForApp(AppType.OIDC, "app")).thenReturn(views);

        assertThat(service.assignmentsForApp(AppType.OIDC, "app")).isSameAs(views);
        verify(assignments).assignmentsForApp(AppType.OIDC, "app");
    }

    @Test
    void assignDelegatesToAssignments() {
        AssignAppRequest request = new AssignAppRequest("OIDC", "app", "USER", UUID.randomUUID().toString(), null);
        AppAssignmentView view = new AppAssignmentView("id", "OIDC", "app", "App", "USER", "sid", "name", null);
        when(assignments.assign(request)).thenReturn(view);

        assertThat(service.assign(request)).isSameAs(view);
        verify(assignments).assign(request);
    }

    @Test
    void unassignDelegatesToAssignments() {
        UUID id = UUID.randomUUID();

        service.unassign(id);

        verify(assignments).unassign(id);
    }
}
