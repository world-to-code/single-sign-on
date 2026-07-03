package com.example.sso.portal.internal.application;

import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.portal.internal.domain.AppAssignment;
import com.example.sso.portal.internal.domain.AppAssignment.SubjectType;
import com.example.sso.portal.internal.domain.AppAssignmentRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

        return list.stream().map(a -> AppAssignmentView.of(a, appName, subjectName(names, a))).toList();
    }

    @Transactional
    AppAssignmentView assign(AssignAppRequest request) {
        AppType appType = AppType.valueOf(request.appType());
        SubjectType subjectType = SubjectType.valueOf(request.subjectType());
        UUID subjectId = UUID.fromString(request.subjectId());
        if (assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(appType, request.appId(), subjectType, subjectId)) {
            throw new ConflictException("application is already assigned to that subject");
        }

        UUID policyId = request.requiredPolicyId() == null || request.requiredPolicyId().isBlank()
                ? null : UUID.fromString(request.requiredPolicyId());
        AppAssignment saved = assignments.save(new AppAssignment(appType, request.appId(), subjectType, subjectId, policyId));
        ApplicationView app = catalog.index().get(AppKey.of(appType, request.appId()));
        String appName = app == null ? request.appId() : app.name();

        return AppAssignmentView.of(saved, appName, subjectName(subjectNames(List.of(saved)), saved));
    }

    @Transactional
    void unassign(UUID assignmentId) {
        if (!assignments.existsById(assignmentId)) {
            throw new NotFoundException("assignment not found");
        }
        assignments.deleteById(assignmentId);
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
