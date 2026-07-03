package com.example.sso.portal.internal.application;

import com.example.sso.portal.AppAccess;
import com.example.sso.portal.AppAccessQuery;
import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.user.UserAccount;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Default {@link ApplicationService}: a thin facade over three cohesive collaborators — the
 * {@link AppCatalog} (unified OIDC+SAML app list), the {@link AppAssignmentManager} (portal
 * subject↔app assignments), and the {@link AppAccessResolver} (per-app policy + step-up gating).
 */
@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final AppCatalog catalog;
    private final AppAssignmentManager assignments;
    private final AppAccessResolver access;

    @Override
    public List<ApplicationView> listApplications() {
        return catalog.list();
    }

    @Override
    public AppAccess appAccess(AppAccessQuery query) {
        return access.appAccess(query);
    }

    @Override
    public void setAppPolicy(AppType appType, String appId, String requiredPolicyId) {
        access.setAppPolicy(appType, appId, requiredPolicyId);
    }

    @Override
    public List<ApplicationView> appsForUser(UserAccount user) {
        return assignments.appsForUser(user);
    }

    @Override
    public boolean hasAssignment(UserAccount user, AppType appType, String appId) {
        return assignments.hasAssignment(user, appType, appId);
    }

    @Override
    public List<ApplicationView> appsForGroup(UUID groupId) {
        return assignments.appsForGroup(groupId);
    }

    @Override
    public List<AppAssignmentView> assignmentsForApp(AppType appType, String appId) {
        return assignments.assignmentsForApp(appType, appId);
    }

    @Override
    public AppAssignmentView assign(AssignAppRequest request) {
        return assignments.assign(request);
    }

    @Override
    public void unassign(UUID assignmentId) {
        assignments.unassign(assignmentId);
    }
}
