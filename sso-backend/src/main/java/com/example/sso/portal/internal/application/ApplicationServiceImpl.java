package com.example.sso.portal.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.portal.AppAccess;
import com.example.sso.portal.AppAccessQuery;
import com.example.sso.portal.internal.domain.AppAssignment;
import com.example.sso.portal.AppType;
import com.example.sso.portal.internal.domain.AppAssignment.SubjectType;
import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.ApplicationDescriptor;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationSource;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.portal.internal.domain.AppAssignmentRepository;
import com.example.sso.portal.internal.domain.AppPolicy;
import com.example.sso.portal.internal.domain.AppPolicyRepository;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Default {@link ApplicationService}: unifies OIDC clients + SAML SPs and resolves portal assignments. */
@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    /** Each protocol module contributes its launchable apps here (OIDC in admin, SAML in saml). */
    private final List<ApplicationSource> applicationSources;
    private final AppAssignmentRepository assignments;
    private final AppPolicyRepository appPolicies;
    private final UserService users;
    private final RoleService roles;
    private final UserGroupRepository userGroups;
    private final AuthPolicyResolver authPolicies;
    private final AuthPolicyEvaluator evaluator;

    @Override
    @Transactional(readOnly = true)
    public AppAccess appAccess(AppAccessQuery query) {
        UserAccount user = query.user();
        Optional<AuthPolicyView> resolved = resolveAppPolicy(user, query.appType(), query.appId());
        if (resolved.isEmpty()) {
            return new AppAccess(true, List.of());
        }

        AuthPolicyView policy = resolved.get();
        // 1) Acquire any factor the user does not yet hold.
        Optional<AuthPolicyStepView> missing = evaluator.currentStep(policy, query.grantedFactors());
        if (missing.isPresent()) {
            return new AppAccess(false, factorNames(missing.get()));
        }

        // 2) All factors held — require a fresh deliberate step-up for this app.
        Duration window = Duration.ofMinutes(policy.getStepUpFreshnessMinutes());
        Instant lastAppStepUp = query.lastAppStepUp();
        boolean fresh = lastAppStepUp != null && !Duration.between(lastAppStepUp, Instant.now()).minus(window).isPositive();
        if (fresh || policy.getSteps().isEmpty()) {
            return new AppAccess(true, List.of());
        }

        // Re-prove the final (strongest) step to refresh the window.
        AuthPolicyStepView last = policy.getSteps().get(policy.getSteps().size() - 1);
        return new AppAccess(false, factorNames(last));
    }

    private List<String> factorNames(AuthPolicyStepView step) {
        return step.getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
    }

    /**
     * The highest-priority enabled policy required to access this app: the app-level sign-on policy
     * (applies to everyone) plus any per-subject assignment policy matching the user (directly/via role or group).
     */
    private Optional<AuthPolicyView> resolveAppPolicy(UserAccount user, AppType appType, String appId) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.findGroupIdsByMember(user.getId()));
        List<UUID> candidateIds = new ArrayList<>();

        assignments.findByAppTypeAndAppId(appType, appId).stream()
                .filter(a -> a.getRequiredPolicyId() != null)
                .filter(a -> subjectMatches(a, user.getId(), roleIds, groupIds))
                .forEach(a -> candidateIds.add(a.getRequiredPolicyId()));
        appPolicies.findByAppTypeAndAppId(appType, appId).ifPresent(ap -> candidateIds.add(ap.getRequiredPolicyId()));

        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }

        return authPolicies.highestPriorityEnabled(candidateIds);
    }

    private boolean subjectMatches(AppAssignment a, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds) {
        return switch (a.getSubjectType()) {
            case USER -> a.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(a.getSubjectId());
            case GROUP -> groupIds.contains(a.getSubjectId());
        };
    }

    @Override
    @Transactional
    public void setAppPolicy(AppType appType, String appId, String requiredPolicyId) {
        appPolicies.deleteByAppTypeAndAppId(appType, appId); // one policy per app: replace any existing

        if (requiredPolicyId != null && !requiredPolicyId.isBlank()) {
            UUID policyId = UUID.fromString(requiredPolicyId);
            if (!authPolicies.exists(policyId)) {
                throw new NotFoundException("policy not found");
            }

            appPolicies.save(new AppPolicy(appType, appId, policyId));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationView> listApplications() {
        return new ArrayList<>(indexApplications().values());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationView> appsForUser(UserAccount user) {
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.findGroupIdsByMember(user.getId()));
        List<AppAssignment> matched = new ArrayList<>(
                assignments.findBySubjectTypeAndSubjectId(SubjectType.USER, user.getId()));
        if (!roleIds.isEmpty()) {
            matched.addAll(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.ROLE, roleIds));
        }
        if (!groupIds.isEmpty()) {
            matched.addAll(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.GROUP, groupIds));
        }

        Map<String, ApplicationView> index = indexApplications();
        // The admin console is NOT special-cased here: a seeded ROLE_ADMIN assignment covers admins,
        // and console entry itself is assignment-enforced at /oauth2/authorize (AppAssignmentFilter).
        return matched.stream()
                .map(a -> index.get(key(a.getAppType(), a.getAppId())))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(ApplicationView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAssignment(UserAccount user, AppType appType, String appId) {
        if (assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                appType, appId, SubjectType.USER, user.getId())) {
            return true;
        }
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.findGroupIdsByMember(user.getId()));
        return assignments.findByAppTypeAndAppId(appType, appId).stream()
                .anyMatch(a -> (a.getSubjectType() == SubjectType.ROLE && roleIds.contains(a.getSubjectId()))
                        || (a.getSubjectType() == SubjectType.GROUP && groupIds.contains(a.getSubjectId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApplicationView> appsForGroup(UUID groupId) {
        Map<String, ApplicationView> index = indexApplications();
        return assignments.findBySubjectTypeAndSubjectId(SubjectType.GROUP, groupId).stream()
                .map(a -> index.get(key(a.getAppType(), a.getAppId())))
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(ApplicationView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppAssignmentView> assignmentsForApp(AppType appType, String appId) {
        Map<String, ApplicationView> index = indexApplications();
        ApplicationView app = index.get(key(appType, appId));
        String appName = app == null ? appId : app.name();
        List<AppAssignment> list = assignments.findByAppTypeAndAppId(appType, appId);
        Map<UUID, String> names = subjectNames(list);

        return list.stream().map(a -> toView(a, appName, names)).toList();
    }

    @Override
    @Transactional
    public AppAssignmentView assign(AssignAppRequest request) {
        AppType appType = AppType.valueOf(request.appType());
        SubjectType subjectType = SubjectType.valueOf(request.subjectType());
        UUID subjectId = UUID.fromString(request.subjectId());
        if (assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(appType, request.appId(), subjectType, subjectId)) {
            throw new ConflictException("application is already assigned to that subject");
        }

        UUID policyId = request.requiredPolicyId() == null || request.requiredPolicyId().isBlank()
                ? null : UUID.fromString(request.requiredPolicyId());
        AppAssignment saved = assignments.save(new AppAssignment(appType, request.appId(), subjectType, subjectId, policyId));
        ApplicationView app = indexApplications().get(key(appType, request.appId()));

        return toView(saved, app == null ? request.appId() : app.name(), subjectNames(List.of(saved)));
    }

    @Override
    @Transactional
    public void unassign(UUID assignmentId) {
        if (!assignments.existsById(assignmentId)) {
            throw new NotFoundException("assignment not found");
        }
        assignments.deleteById(assignmentId);
    }

    // --- internals ---

    private Map<String, ApplicationView> indexApplications() {
        // app-level sign-on policy per app + policy-id -> name (one lookup pass, not per-app queries)
        Map<String, UUID> appPolicyByKey = appPolicies.findAll().stream()
                .collect(Collectors.toMap(ap -> key(ap.getAppType(), ap.getAppId()), AppPolicy::getRequiredPolicyId, (a, b) -> a));
        Map<UUID, String> policyNames = authPolicies.policyNames().stream()
                .collect(Collectors.toMap(IdName::getId, IdName::getName));

        Map<String, ApplicationView> index = new LinkedHashMap<>();
        for (ApplicationSource source : applicationSources) {
            for (ApplicationDescriptor app : source.applications()) {
                index.put(key(app.type(), app.id()), appView(app, appPolicyByKey, policyNames));
            }
        }

        return index;
    }

    private ApplicationView appView(ApplicationDescriptor app, Map<String, UUID> appPolicyByKey,
                                    Map<UUID, String> policyNames) {
        UUID policyId = appPolicyByKey.get(key(app.type(), app.id()));
        return new ApplicationView(app.id(), app.type().name(), app.name(), app.launchUrl(), app.system(),
                policyId == null ? null : policyId.toString(),
                policyId == null ? null : policyNames.get(policyId));
    }

    private String key(AppType type, String id) {
        return type + ":" + id;
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
            userGroups.findIdNames(groupIds).forEach(p -> names.put(p.getId(), p.getName()));
        }

        return names;
    }

    private Set<UUID> subjectIds(Collection<AppAssignment> list, SubjectType type) {
        return list.stream().filter(a -> a.getSubjectType() == type)
                .map(AppAssignment::getSubjectId).collect(Collectors.toSet());
    }

    private AppAssignmentView toView(AppAssignment a, String appName, Map<UUID, String> subjectNames) {
        String subjectName = subjectNames.getOrDefault(a.getSubjectId(), a.getSubjectId().toString());

        return new AppAssignmentView(a.getId().toString(), a.getAppType().name(), a.getAppId(), appName,
                a.getSubjectType().name(), a.getSubjectId().toString(), subjectName,
                a.getRequiredPolicyId() == null ? null : a.getRequiredPolicyId().toString());
    }
}
