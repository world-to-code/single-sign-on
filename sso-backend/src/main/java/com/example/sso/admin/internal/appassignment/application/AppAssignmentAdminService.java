package com.example.sso.admin.internal.appassignment.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ForbiddenException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Scope-enforcing adapter over the portal {@link ApplicationService} for the admin app-assignment API:
 * filters the app list and gates every by-app read/mutation to the acting admin's resource subtree
 * (super admin bypasses). {@code assign} scopes the app but deliberately NOT the SUBJECT (an app owner may
 * grant it to any principal). {@code unassign} resolves the assignment's app first and gates it through the
 * same {@code requireAccess} as {@code assign}, so a delegated admin can only remove assignments for apps in
 * its managed subtree (the portal additionally org-tier confines via {@code OrgTierGuard.requireInTier}).
 */
@Service
@RequiredArgsConstructor
public class AppAssignmentAdminService {

    private final ApplicationService applications;
    private final AdminAccessPolicy accessPolicy;

    public Page<ApplicationView> listApplications(int page, int size) {
        List<ApplicationView> all = applications.listApplications();
        if (!accessPolicy.isCurrentActorUnscoped()) {
            Set<String> scoped = accessPolicy.currentScopedAppIds();
            all = all.stream().filter(app -> scoped.contains(app.id())).toList();
        }
        return Page.of(all, page, size);
    }

    public List<AppAssignmentView> assignmentsForApp(AppType appType, String appId) {
        requireAccess(appId);
        return applications.assignmentsForApp(appType, appId);
    }

    public AppAssignmentView assign(AssignAppRequest request) {
        requireAccess(request.appId());
        return applications.assign(request);
    }

    public void unassign(UUID assignmentId) {
        // Subtree-confine within a tenant: resolve the assignment's app and gate it like assign, so a delegated
        // admin only removes assignments for apps it manages. A missing/foreign assignment resolves empty here
        // and the portal's tier check then returns a non-revealing 404.
        applications.findAssignment(assignmentId).ifPresent(assignment -> requireAccess(assignment.appId()));
        applications.unassign(assignmentId);
    }

    public void setAppPolicy(AppType appType, String appId, String requiredPolicyId) {
        requireAccess(appId);
        applications.setAppPolicy(appType, appId, requiredPolicyId);
    }

    private void requireAccess(String appId) {
        if (!accessPolicy.canAccessApp(appId)) {
            throw new ForbiddenException("Outside your managed applications.");
        }
    }
}
