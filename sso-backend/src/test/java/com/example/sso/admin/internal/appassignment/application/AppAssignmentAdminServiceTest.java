package com.example.sso.admin.internal.appassignment.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.shared.error.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppAssignmentAdminService}. Focus: every by-app read/mutation is gated by
 * {@link AdminAccessPolicy#canAccessApp} and must NOT delegate to the portal {@link ApplicationService}
 * when the app is outside the actor's scope (403); {@code listApplications} filters to scoped apps.
 */
class AppAssignmentAdminServiceTest {

    private static final String APP_ID = "app-1";

    private ApplicationService applications;
    private AdminAccessPolicy accessPolicy;
    private AppAssignmentAdminService service;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        service = new AppAssignmentAdminService(applications, accessPolicy);
    }

    @Test
    void assignmentsForAppOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessApp(APP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.assignmentsForApp(AppType.OIDC, APP_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(applications, never()).assignmentsForApp(any(), any());
    }

    @Test
    void assignOutsideScopeIsForbiddenAndDoesNotDelegate() {
        AssignAppRequest request = new AssignAppRequest("OIDC", APP_ID, "USER", "u-1", null);
        when(accessPolicy.canAccessApp(APP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.assign(request)).isInstanceOf(ForbiddenException.class);
        verify(applications, never()).assign(any());
    }

    @Test
    void setAppPolicyOutsideScopeIsForbiddenAndDoesNotDelegate() {
        when(accessPolicy.canAccessApp(APP_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.setAppPolicy(AppType.OIDC, APP_ID, "policy-1"))
                .isInstanceOf(ForbiddenException.class);
        verify(applications, never()).setAppPolicy(any(), any(), any());
    }

    @Test
    void setAppPolicyInScopeDelegates() {
        when(accessPolicy.canAccessApp(APP_ID)).thenReturn(true);

        service.setAppPolicy(AppType.OIDC, APP_ID, "policy-1");

        verify(applications).setAppPolicy(AppType.OIDC, APP_ID, "policy-1");
    }

    @Test
    void listApplicationsFiltersToScopedAppsForAScopedActor() {
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentScopedAppIds()).thenReturn(Set.of(APP_ID));
        when(applications.listApplications()).thenReturn(List.of(app(APP_ID), app("app-2")));

        assertThat(service.listApplications()).extracting(ApplicationView::id).containsExactly(APP_ID);
    }

    @Test
    void listApplicationsReturnsEverythingForAnUnscopedActor() {
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(applications.listApplications()).thenReturn(List.of(app(APP_ID), app("app-2")));

        assertThat(service.listApplications()).hasSize(2);
        verify(accessPolicy, never()).currentScopedAppIds();
    }

    private ApplicationView app(String id) {
        return new ApplicationView(id, "OIDC", "name-" + id, "https://launch/" + id, false, null, null);
    }
}
