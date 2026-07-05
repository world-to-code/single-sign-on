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
 * (super admin bypasses). Two deliberate non-confinements: {@code assign} scopes the app but not the
 * SUBJECT (an app owner may grant it to any principal), and {@code unassign} carries only the assignment
 * id — it is now org-tier confined in the portal ({@code OrgTierGuard.requireInTier}, so a tenant admin
 * can only remove its OWN org's assignments), but not yet resource-subtree confined WITHIN a tenant.
 * Since {@code app-assignment:unassign} became tenant-grantable, two delegated admins in one org with
 * disjoint subtrees can each remove the other's assignment; closing that needs a portal assignment→app
 * lookup to gate {@code unassign} through {@code requireAccess} like {@code assign} (tracked follow-up).
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
