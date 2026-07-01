package com.example.sso.portal;

import com.example.sso.admin.ClientAdminService;
import com.example.sso.admin.ClientView;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicy;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyRepository;
import com.example.sso.authpolicy.AuthPolicyStep;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.portal.AppAssignment.AppType;
import com.example.sso.portal.AppAssignment.SubjectType;
import com.example.sso.saml.SamlRelyingParty;
import com.example.sso.saml.SamlRelyingPartyRepository;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.Role;
import com.example.sso.user.RoleRepository;
import com.example.sso.user.UserGroupRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/** Unifies OIDC clients + SAML SPs as launchable "applications" and resolves portal assignments. */
@Service
public class ApplicationService {

    private final ClientAdminService clients;
    private final SamlRelyingPartyRepository samlRelyingParties;
    private final AppAssignmentRepository assignments;
    private final AppPolicyRepository appPolicies;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserGroupRepository userGroups;
    private final AuthPolicyRepository policies;
    private final AuthPolicyEvaluator evaluator;
    private final String issuer;

    public ApplicationService(ClientAdminService clients, SamlRelyingPartyRepository samlRelyingParties,
                              AppAssignmentRepository assignments, AppPolicyRepository appPolicies,
                              AppUserRepository users, RoleRepository roles, UserGroupRepository userGroups,
                              AuthPolicyRepository policies, AuthPolicyEvaluator evaluator,
                              @Value("${sso.issuer}") String issuer) {
        this.clients = clients;
        this.samlRelyingParties = samlRelyingParties;
        this.assignments = assignments;
        this.appPolicies = appPolicies;
        this.users = users;
        this.roles = roles;
        this.userGroups = userGroups;
        this.policies = policies;
        this.evaluator = evaluator;
        this.issuer = issuer;
    }

    /**
     * Evaluates whether {@code user} may launch the app. Beyond holding the required factors, a policy
     * attached to an app requires a <b>deliberate, recent</b> step-up for that app: {@code lastAppStepUp}
     * (stamped only when the user completes an app step-up, never at plain login) must fall within the
     * policy's freshness window. So an attached policy always challenges on entry — and again once the
     * window lapses — instead of silently passing on factors already held from login.
     */
    @Transactional(readOnly = true)
    public AppAccess appAccess(AppUser user, AppType appType, String appId, Set<String> grantedFactors,
                               Instant lastAppStepUp) {
        Optional<AuthPolicy> resolved = resolveAppPolicy(user, appType, appId);
        if (resolved.isEmpty()) {
            return new AppAccess(true, List.of());
        }
        AuthPolicy policy = resolved.get();
        // 1) Acquire any factor the user does not yet hold.
        Optional<AuthPolicyStep> missing = evaluator.currentStep(policy, grantedFactors);
        if (missing.isPresent()) {
            return new AppAccess(false, factorNames(missing.get()));
        }
        // 2) All factors held — require a fresh deliberate step-up for this app.
        Duration window = Duration.ofMinutes(policy.getStepUpFreshnessMinutes());
        boolean fresh = lastAppStepUp != null && !Duration.between(lastAppStepUp, Instant.now()).minus(window).isPositive();
        if (fresh || policy.getSteps().isEmpty()) {
            return new AppAccess(true, List.of());
        }
        // Re-prove the final (strongest) step to refresh the window.
        AuthPolicyStep last = policy.getSteps().get(policy.getSteps().size() - 1);
        return new AppAccess(false, factorNames(last));
    }

    private static List<String> factorNames(AuthPolicyStep step) {
        return step.getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
    }

    /**
     * The highest-priority enabled policy required to access this app: the app-level sign-on policy
     * (applies to everyone) plus any per-subject assignment policy matching the user (directly/via role).
     */
    private Optional<AuthPolicy> resolveAppPolicy(AppUser user, AppType appType, String appId) {
        Set<UUID> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
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
        return policies.findAllById(candidateIds).stream() // one query (collections batch-fetched)
                .filter(AuthPolicy::isEnabled)
                .max(Comparator.comparingInt(AuthPolicy::getPriority));
    }

    private static boolean subjectMatches(AppAssignment a, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds) {
        return switch (a.getSubjectType()) {
            case USER -> a.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(a.getSubjectId());
            case GROUP -> groupIds.contains(a.getSubjectId());
        };
    }

    /** Sets (or clears, when {@code requiredPolicyId} is blank/null) the app-level sign-on policy. */
    @Transactional
    public void setAppPolicy(AppType appType, String appId, String requiredPolicyId) {
        appPolicies.deleteByAppTypeAndAppId(appType, appId); // one policy per app: replace any existing
        if (requiredPolicyId != null && !requiredPolicyId.isBlank()) {
            UUID policyId = UUID.fromString(requiredPolicyId);
            if (policies.findById(policyId).isEmpty()) {
                throw new NotFoundException("policy not found");
            }
            appPolicies.save(new AppPolicy(appType, appId, policyId));
        }
    }

    /** All registered applications (OIDC + SAML), for the admin dashboard. */
    @Transactional(readOnly = true)
    public List<ApplicationView> listApplications() {
        return new ArrayList<>(indexApplications().values());
    }

    /** The applications a user may launch (assigned directly or via one of their roles/groups). */
    @Transactional(readOnly = true)
    public List<ApplicationView> appsForUser(AppUser user) {
        Set<UUID> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
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
        List<ApplicationView> apps = new ArrayList<>(matched.stream()
                .map(a -> index.get(key(a.getAppType(), a.getAppId())))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        // The admin console is auto-granted to any admin — no explicit assignment needed.
        if (user.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName()))) {
            index.values().stream().filter(ApplicationView::system)
                    .filter(app -> !apps.contains(app))
                    .forEach(apps::add);
        }
        return apps.stream()
                .sorted(Comparator.comparing(ApplicationView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Applications assigned directly to a group (for the group detail page). */
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

    @Transactional(readOnly = true)
    public List<AppAssignmentView> assignmentsForApp(AppType appType, String appId) {
        Map<String, ApplicationView> index = indexApplications();
        ApplicationView app = index.get(key(appType, appId));
        String appName = app == null ? appId : app.name();
        List<AppAssignment> list = assignments.findByAppTypeAndAppId(appType, appId);
        Map<UUID, String> names = subjectNames(list);
        return list.stream().map(a -> toView(a, appName, names)).toList();
    }

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
        Map<UUID, String> policyNames = policies.findIdNames().stream()
                .collect(Collectors.toMap(IdName::getId, IdName::getName));

        Map<String, ApplicationView> index = new LinkedHashMap<>();
        for (ClientView c : clients.listClients()) {
            boolean system = AdminPortalSeeder.CLIENT_ID.equals(c.clientId());
            String name = system ? "Admin Portal"
                    : (c.clientName() == null || c.clientName().isBlank() ? c.clientId() : c.clientName());
            String launchUrl = system ? "/admin" : oidcLaunchUrl(c);
            index.put(key(AppType.OIDC, c.id()), appView(c.id(), "OIDC", name, launchUrl, system, appPolicyByKey, policyNames));
        }
        for (SamlRelyingParty rp : samlRelyingParties.findAll()) {
            index.put(key(AppType.SAML, rp.getId().toString()),
                    appView(rp.getId().toString(), "SAML", rp.getEntityId(), samlLaunchUrl(rp), false, appPolicyByKey, policyNames));
        }
        return index;
    }

    private ApplicationView appView(String id, String type, String name, String launchUrl, boolean system,
                                    Map<String, UUID> appPolicyByKey, Map<UUID, String> policyNames) {
        UUID policyId = appPolicyByKey.get(type + ":" + id);
        return new ApplicationView(id, type, name, launchUrl, system,
                policyId == null ? null : policyId.toString(),
                policyId == null ? null : policyNames.get(policyId));
    }

    private static String key(AppType type, String id) {
        return type + ":" + id;
    }

    /**
     * OIDC launch: prefer the RP's {@code initiate_login_uri} (OIDC Core §4 third-party-initiated
     * login) with the required {@code iss} parameter, so the RP starts its own RP-initiated flow.
     * Falls back to the app's origin (derived from its first redirect URI) when none is configured.
     */
    private String oidcLaunchUrl(ClientView client) {
        if (StringUtils.hasText(client.initiateLoginUri())) {
            String uri = client.initiateLoginUri().trim();
            String sep = uri.contains("?") ? "&" : "?";
            return uri + sep + "iss=" + UriUtils.encodeQueryParam(issuer, StandardCharsets.UTF_8);
        }
        String first = client.redirectUris() == null ? "" : client.redirectUris().split("[,\\s]+")[0].trim();
        if (first.isEmpty()) {
            return null;
        }
        try {
            URI uri = URI.create(first);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * SAML launch: prefer the SP's own SP-initiated login start URL (the SP then sends us an
     * AuthnRequest — the standard, secure flow). Falls back to IdP-initiated (unsolicited) SSO.
     */
    private static String samlLaunchUrl(SamlRelyingParty rp) {
        if (StringUtils.hasText(rp.getSpLoginUrl())) {
            return rp.getSpLoginUrl().trim();
        }
        return "/saml2/idp/sso/init?sp=" + UriUtils.encodeQueryParam(rp.getEntityId(), StandardCharsets.UTF_8);
    }

    /** Resolves subject (user/role) display names for a batch of assignments in two queries, not per-row. */
    private Map<UUID, String> subjectNames(Collection<AppAssignment> list) {
        Set<UUID> userIds = subjectIds(list, SubjectType.USER);
        Set<UUID> roleIds = subjectIds(list, SubjectType.ROLE);
        Set<UUID> groupIds = subjectIds(list, SubjectType.GROUP);
        Map<UUID, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            users.findIdNames(userIds).forEach(p -> names.put(p.getId(), p.getName()));
        }
        if (!roleIds.isEmpty()) {
            roles.findIdNames(roleIds).forEach(p -> names.put(p.getId(), p.getName()));
        }
        if (!groupIds.isEmpty()) {
            userGroups.findIdNames(groupIds).forEach(p -> names.put(p.getId(), p.getName()));
        }
        return names;
    }

    private static Set<UUID> subjectIds(Collection<AppAssignment> list, SubjectType type) {
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
