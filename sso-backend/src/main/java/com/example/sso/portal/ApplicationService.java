package com.example.sso.portal;

import com.example.sso.portal.AppType;
import com.example.sso.user.UserAccount;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Portal module's public contract: unifies OIDC clients + SAML SPs as launchable "applications" and
 * resolves portal assignments and per-app step-up policies. The implementation stays module-internal.
 */
public interface ApplicationService {

    /**
     * Evaluates whether {@code user} may launch the app: beyond holding the required factors, an
     * attached policy requires a deliberate, recent step-up ({@code lastAppStepUp} within the policy's
     * freshness window), so it always challenges on entry and again once the window lapses.
     */
    AppAccess appAccess(UserAccount user, AppType appType, String appId, Set<String> grantedFactors,
                        Instant lastAppStepUp);

    /** Sets (or clears, when {@code requiredPolicyId} is blank/null) the app-level sign-on policy. */
    void setAppPolicy(AppType appType, String appId, String requiredPolicyId);

    /** All registered applications (OIDC + SAML), for the admin dashboard. */
    List<ApplicationView> listApplications();

    /** The applications a user may launch (assigned directly or via one of their roles/groups). */
    List<ApplicationView> appsForUser(UserAccount user);

    /** Applications assigned directly to a group (for the group detail page). */
    List<ApplicationView> appsForGroup(UUID groupId);

    List<AppAssignmentView> assignmentsForApp(AppType appType, String appId);

    AppAssignmentView assign(AssignAppRequest request);

    void unassign(UUID assignmentId);
}
