package com.example.sso.portal;

import com.example.sso.admin.ClientAdminService;
import com.example.sso.admin.ClientView;
import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicy;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyRepository;
import com.example.sso.authpolicy.AuthPolicyStep;
import com.example.sso.portal.AppAssignment.AppType;
import com.example.sso.portal.AppAssignment.SubjectType;
import com.example.sso.saml.SamlRelyingParty;
import com.example.sso.saml.SamlRelyingPartyRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.Role;
import com.example.sso.user.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Unifies OIDC clients + SAML SPs as launchable "applications" and resolves portal assignments. */
@Service
public class ApplicationService {

    private final ClientAdminService clients;
    private final SamlRelyingPartyRepository samlRelyingParties;
    private final AppAssignmentRepository assignments;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final AuthPolicyRepository policies;
    private final AuthPolicyEvaluator evaluator;

    public ApplicationService(ClientAdminService clients, SamlRelyingPartyRepository samlRelyingParties,
                              AppAssignmentRepository assignments, AppUserRepository users, RoleRepository roles,
                              AuthPolicyRepository policies, AuthPolicyEvaluator evaluator) {
        this.clients = clients;
        this.samlRelyingParties = samlRelyingParties;
        this.assignments = assignments;
        this.users = users;
        this.roles = roles;
        this.policies = policies;
        this.evaluator = evaluator;
    }

    /**
     * Evaluates whether {@code user} (with the factors already satisfied this session) may launch the
     * app, or must complete additional step-up factors required by the app's per-assignment policy.
     */
    @Transactional(readOnly = true)
    public AppAccess appAccess(AppUser user, AppType appType, String appId, Set<String> grantedFactors) {
        Optional<AuthPolicy> required = resolveAppPolicy(user, appType, appId);
        if (required.isEmpty()) {
            return new AppAccess(true, List.of());
        }
        Optional<AuthPolicyStep> step = evaluator.currentStep(required.get(), grantedFactors);
        if (step.isEmpty()) {
            return new AppAccess(true, List.of());
        }
        List<String> pending = step.get().getAllowedFactors().stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(AuthFactor::name).toList();
        return new AppAccess(false, pending);
    }

    /** The highest-priority enabled per-app policy assigned to the user (directly or via a role). */
    private Optional<AuthPolicy> resolveAppPolicy(AppUser user, AppType appType, String appId) {
        Set<UUID> roleIds = user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
        return assignments.findByAppTypeAndAppId(appType, appId).stream()
                .filter(a -> a.getRequiredPolicyId() != null)
                .filter(a -> (a.getSubjectType() == SubjectType.USER && a.getSubjectId().equals(user.getId()))
                        || (a.getSubjectType() == SubjectType.ROLE && roleIds.contains(a.getSubjectId())))
                .map(a -> policies.findById(a.getRequiredPolicyId()))
                .flatMap(Optional::stream)
                .filter(AuthPolicy::isEnabled)
                .max(Comparator.comparingInt(AuthPolicy::getPriority));
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
        List<AppAssignment> matched = new ArrayList<>(
                assignments.findBySubjectTypeAndSubjectId(SubjectType.USER, user.getId()));
        if (!roleIds.isEmpty()) {
            matched.addAll(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.ROLE, roleIds));
        }
        Map<String, ApplicationView> index = indexApplications();
        return matched.stream()
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
        return assignments.findByAppTypeAndAppId(appType, appId).stream()
                .map(a -> toView(a, appName))
                .toList();
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
        return toView(saved, app == null ? request.appId() : app.name());
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
        Map<String, ApplicationView> index = new LinkedHashMap<>();
        for (ClientView c : clients.listClients()) {
            String name = c.clientName() == null || c.clientName().isBlank() ? c.clientId() : c.clientName();
            index.put(key(AppType.OIDC, c.id()), new ApplicationView(c.id(), "OIDC", name, oidcLaunchUrl(c)));
        }
        for (SamlRelyingParty rp : samlRelyingParties.findAll()) {
            index.put(key(AppType.SAML, rp.getId().toString()),
                    new ApplicationView(rp.getId().toString(), "SAML", rp.getEntityId(), samlLaunchUrl(rp)));
        }
        return index;
    }

    private static String key(AppType type, String id) {
        return type + ":" + id;
    }

    /** OIDC: the app's own origin (derived from its first redirect URI); it redirects back to us to sign in. */
    private static String oidcLaunchUrl(ClientView client) {
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

    /** SAML: IdP-initiated SSO into the SP. */
    private static String samlLaunchUrl(SamlRelyingParty rp) {
        return "/saml2/idp/sso/init?sp=" + UriUtils.encodeQueryParam(rp.getEntityId(), StandardCharsets.UTF_8);
    }

    private AppAssignmentView toView(AppAssignment a, String appName) {
        String subjectName = a.getSubjectType() == SubjectType.USER
                ? users.findById(a.getSubjectId()).map(AppUser::getUsername).orElse(a.getSubjectId().toString())
                : roles.findById(a.getSubjectId()).map(Role::getName).orElse(a.getSubjectId().toString());
        return new AppAssignmentView(a.getId().toString(), a.getAppType().name(), a.getAppId(), appName,
                a.getSubjectType().name(), a.getSubjectId().toString(), subjectName,
                a.getRequiredPolicyId() == null ? null : a.getRequiredPolicyId().toString());
    }
}
