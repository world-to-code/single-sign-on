package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.access.AppAssignmentFilter;
import com.example.sso.portal.access.AppAssignmentView;
import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.access.AssignAppRequest;
import com.example.sso.portal.internal.catalog.domain.AppAssignment;
import com.example.sso.portal.internal.catalog.domain.AppAssignment.SubjectType;
import com.example.sso.portal.internal.catalog.domain.AppAssignmentRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal application assignments: which users/roles/groups an app is granted to, and the reverse (the
 * apps a user or group can launch). Resolves app and subject display names in batch, drawing app names
 * from the {@link AppCatalog}.
 */
@Service
@RequiredArgsConstructor
class AppAssignmentManager {

    private final AppAssignmentRepository assignments;
    private final UserService users;
    private final RoleService roles;
    private final UserGroupService userGroups;
    private final AppCatalog catalog;
    private final OrgTierGuard tierGuard;
    private final AuthPolicyResolver authPolicies;

    @Transactional(readOnly = true)
    List<ApplicationView> appsForUser(UserAccount user) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(user.getId()));
        List<AppAssignment> matched = new ArrayList<>(
                assignments.findBySubjectTypeAndSubjectId(SubjectType.USER, user.getId()));
        if (!roleIds.isEmpty()) {
            matched.addAll(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.ROLE, roleIds));
        }
        if (!groupIds.isEmpty()) {
            matched.addAll(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.GROUP, groupIds));
        }

        Map<String, ApplicationView> index = catalog.index();
        // The admin console is NOT special-cased here: a seeded ROLE_ADMIN assignment covers admins,
        // and console entry itself is assignment-enforced at /oauth2/authorize (AppAssignmentFilter).
        return matched.stream()
                .map(a -> index.get(AppKey.of(a.getAppType(), a.getAppId())))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(ApplicationView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    boolean hasAssignment(UserAccount user, AppType appType, String appId) {
        if (assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                appType, appId, SubjectType.USER, user.getId())) {
            return true;
        }
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(user.getId()));
        return assignments.findByAppTypeAndAppId(appType, appId).stream()
                .anyMatch(a -> (a.getSubjectType() == SubjectType.ROLE && roleIds.contains(a.getSubjectId()))
                        || (a.getSubjectType() == SubjectType.GROUP && groupIds.contains(a.getSubjectId())));
    }

    @Transactional(readOnly = true)
    List<ApplicationView> appsForGroup(UUID groupId) {
        Map<String, ApplicationView> index = catalog.index();
        return assignments.findBySubjectTypeAndSubjectId(SubjectType.GROUP, groupId).stream()
                .map(a -> index.get(AppKey.of(a.getAppType(), a.getAppId())))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(ApplicationView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    List<AppAssignmentView> assignmentsForApp(AppType appType, String appId) {
        Map<String, ApplicationView> index = catalog.index();
        ApplicationView app = index.get(AppKey.of(appType, appId));
        String appName = app == null ? appId : app.name();
        List<AppAssignment> list = assignments.findByAppTypeAndAppId(appType, appId);
        Map<UUID, String> names = subjectNames(list);

        return list.stream().map(a -> toView(a, appName, subjectName(names, a))).toList();
    }

    /** Projects one assignment (RLS-scoped read), so the admin layer can gate an unassign by its app. */
    @Transactional(readOnly = true)
    Optional<AppAssignmentView> findAssignment(UUID assignmentId) {
        return assignments.findById(assignmentId).map(this::toView);
    }

    private AppAssignmentView toView(AppAssignment assignment) {
        ApplicationView app = catalog.index().get(AppKey.of(assignment.getAppType(), assignment.getAppId()));
        String appName = app == null ? assignment.getAppId() : app.name();
        return toView(assignment, appName, subjectName(subjectNames(List.of(assignment)), assignment));
    }

    /** Boundary factory: the app and subject display names are resolved by the caller. */
    private AppAssignmentView toView(AppAssignment a, String appName, String subjectName) {
        return new AppAssignmentView(a.getId().toString(), a.getAppType().name(), a.getAppId(), appName,
                a.getSubjectType().name(), a.getSubjectId().toString(), subjectName,
                a.getRequiredPolicyId() == null ? null : a.getRequiredPolicyId().toString());
    }

    @Transactional
    AppAssignmentView assign(AssignAppRequest request) {
        AppType appType = AppType.valueOf(request.appType());
        if (appType == AppType.PORTAL) {
            // Portals are catalog/management-only: a user is never "assigned" to the user portal (everyone
            // uses it), so a PORTAL assignment would only produce a self-referential launch tile.
            throw BadRequestException.of("portal.assignment.notAssignable");
        }
        SubjectType subjectType = SubjectType.valueOf(request.subjectType());
        UUID subjectId = UUID.fromString(request.subjectId());
        if (assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(appType, request.appId(), subjectType, subjectId)) {
            throw ConflictException.of("portal.assignment.duplicate");
        }

        UUID policyId = requiredPolicy(request.requiredPolicyId());
        AppAssignment saved = assignments.save(new AppAssignment(appType, request.appId(), subjectType, subjectId,
                policyId, tierGuard.currentTier())); // stamp the acting admin's tier so it applies only in-org
        ApplicationView app = catalog.index().get(AppKey.of(appType, request.appId()));
        String appName = app == null ? request.appId() : app.name();

        return toView(saved, appName, subjectName(subjectNames(List.of(saved)), saved));
    }

    @Transactional
    void unassign(UUID assignmentId) {
        // Tier-check in code: RLS lets any context READ a global row, so confirm the row is in the caller's
        // tier before deleting (a tenant admin cannot remove a global or another tenant's assignment) — a
        // non-revealing 404 on mismatch, not disclosing that the assignment exists.
        AppAssignment assignment = tierGuard.requireInTier(assignments.findById(assignmentId),
                () -> new NotFoundException("assignment not found"));
        assignments.delete(assignment);
    }

    /** Resolves subject (user/role) display names for a batch of assignments in two queries, not per-row. */
    private Map<UUID, String> subjectNames(Collection<AppAssignment> list) {
        Set<UUID> userIds = subjectIds(list, SubjectType.USER);
        Set<UUID> roleIds = subjectIds(list, SubjectType.ROLE);
        Set<UUID> groupIds = subjectIds(list, SubjectType.GROUP);
        Map<UUID, String> names = new HashMap<>();

        if (!userIds.isEmpty()) {
            users.idNames(userIds).forEach(p -> names.put(p.getId(), p.getName()));
        }
        if (!roleIds.isEmpty()) {
            roles.idNames(roleIds).forEach(p -> names.put(p.getId(), p.getName()));
        }
        if (!groupIds.isEmpty()) {
            userGroups.idNames(groupIds).forEach(p -> names.put(p.getId(), p.getName()));
        }

        return names;
    }

    private Set<UUID> subjectIds(Collection<AppAssignment> list, SubjectType type) {
        return list.stream().filter(a -> a.getSubjectType() == type)
                .map(AppAssignment::getSubjectId).collect(Collectors.toSet());
    }

    /** The resolved display name for an assignment's subject, falling back to its id. */
    private String subjectName(Map<UUID, String> names, AppAssignment a) {
        return names.getOrDefault(a.getSubjectId(), a.getSubjectId().toString());
    }

    /**
     * Resolves (and validates) the assignment's extra-authentication policy. A dangling id would silently
     * resolve to "no policy" at enforcement time, quietly dropping the step-up the admin configured — so an
     * unknown policy is refused here, exactly as the app-level policy path refuses one.
     */
    private UUID requiredPolicy(String requiredPolicyId) {
        if (requiredPolicyId == null || requiredPolicyId.isBlank()) {
            return null;
        }
        UUID policyId = UUID.fromString(requiredPolicyId);
        if (!authPolicies.exists(policyId)) {
            throw new NotFoundException("policy not found");
        }
        return policyId;
    }
}
