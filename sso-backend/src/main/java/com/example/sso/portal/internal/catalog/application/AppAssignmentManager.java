package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.access.AppAssignmentFilter;
import com.example.sso.portal.access.AppAssignmentView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.access.AssignAppRequest;
import com.example.sso.portal.internal.catalog.domain.AppAssignment;
import com.example.sso.portal.internal.catalog.domain.AppAssignment.SubjectType;
import com.example.sso.portal.internal.catalog.domain.AppAssignmentRepository;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
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
    private final AppAuthBinding appAuthBinding;
    private final PolicyBindingRepository bindings;

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
        Map<String, UUID> authByKey = perSubjectAuthPolicies(appType, appId); // ONE binding query for the list

        return list.stream()
                .map(a -> toView(a, appName, subjectName(names, a), authPolicyId(authByKey, a)))
                .toList();
    }

    /** Projects one assignment (RLS-scoped read), so the admin layer can gate an unassign by its app. */
    @Transactional(readOnly = true)
    Optional<AppAssignmentView> findAssignment(UUID assignmentId) {
        return assignments.findById(assignmentId).map(this::toView);
    }

    private AppAssignmentView toView(AppAssignment assignment) {
        ApplicationView app = catalog.index().get(AppKey.of(assignment.getAppType(), assignment.getAppId()));
        String appName = app == null ? assignment.getAppId() : app.name();
        String requiredPolicyId = authPolicyId(
                perSubjectAuthPolicies(assignment.getAppType(), assignment.getAppId()), assignment);
        return toView(assignment, appName, subjectName(subjectNames(List.of(assignment)), assignment), requiredPolicyId);
    }

    /** Boundary factory: the app/subject display names AND the per-subject sign-on policy id are resolved by
     *  the caller (the policy now lives in a separate {@code policy_binding} row, not on the assignment). */
    private AppAssignmentView toView(AppAssignment a, String appName, String subjectName, String requiredPolicyId) {
        return new AppAssignmentView(a.getId().toString(), a.getAppType().name(), a.getAppId(), appName,
                a.getSubjectType().name(), a.getSubjectId().toString(), subjectName, requiredPolicyId);
    }

    /** The per-subject sign-on policy id for each subject of an app, keyed subjectType:subjectId:org — resolved
     *  from the auth bindings in one query so a list view never runs N+1. */
    private Map<String, UUID> perSubjectAuthPolicies(AppType appType, String appId) {
        Map<String, UUID> byKey = new HashMap<>();
        for (PolicyBinding b : bindings.findByAppTypeAndAppId(appType, appId)) {
            if (b.getSubjectType() != null && b.getAuthPolicyId() != null) {
                byKey.put(subjectKey(b.getSubjectType().name(), b.getSubjectId(), b.getOrgId()), b.getAuthPolicyId());
            }
        }
        return byKey;
    }

    private String authPolicyId(Map<String, UUID> byKey, AppAssignment a) {
        UUID id = byKey.get(subjectKey(a.getSubjectType().name(), a.getSubjectId(), a.getOrgId()));
        return id == null ? null : id.toString();
    }

    private String subjectKey(String subjectType, UUID subjectId, UUID orgId) {
        return subjectType + ":" + subjectId + ":" + (orgId == null ? "global" : orgId);
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

        // The ACL row (who may launch) and the optional per-subject sign-on policy (a policy_binding auth row)
        // are written in ONE transaction: a dangling policy id thrown by setForSubject rolls back the ACL too.
        AppAssignment saved = assignments.save(new AppAssignment(appType, request.appId(), subjectType, subjectId,
                tierGuard.currentTier())); // stamp the acting admin's tier so it applies only in-org
        String requiredPolicyId = request.requiredPolicyId();
        if (requiredPolicyId != null && !requiredPolicyId.isBlank()) {
            appAuthBinding.setForSubject(appType, request.appId(),
                    PolicyBinding.SubjectType.valueOf(subjectType.name()), subjectId, UUID.fromString(requiredPolicyId));
        }
        ApplicationView app = catalog.index().get(AppKey.of(appType, request.appId()));
        String appName = app == null ? request.appId() : app.name();

        return toView(saved, appName, subjectName(subjectNames(List.of(saved)), saved),
                requiredPolicyId == null || requiredPolicyId.isBlank() ? null : requiredPolicyId);
    }

    @Transactional
    void unassign(UUID assignmentId) {
        // Tier-check in code: RLS lets any context READ a global row, so confirm the row is in the caller's
        // tier before deleting (a tenant admin cannot remove a global or another tenant's assignment) — a
        // non-revealing 404 on mismatch, not disclosing that the assignment exists.
        AppAssignment assignment = tierGuard.requireInTier(assignments.findById(assignmentId),
                () -> NotFoundException.of("portal.assignment.notFound"));
        assignments.delete(assignment);
        // Drop the per-subject sign-on binding this assignment carried (if any) — no orphan governing a subject
        // that can no longer launch the app.
        appAuthBinding.clearForSubject(assignment.getAppType(), assignment.getAppId(),
                PolicyBinding.SubjectType.valueOf(assignment.getSubjectType().name()), assignment.getSubjectId());
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
}
