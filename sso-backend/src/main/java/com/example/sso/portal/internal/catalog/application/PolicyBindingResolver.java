package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.role.RoleRef;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the effective authentication and session policy for a user in the context of a given app or
 * portal from the {@code policy_binding} matrix. Auth and session are resolved INDEPENDENTLY: for each,
 * the bindings whose subject matches the user and whose field is set are walked most-specific first
 * (USER &gt; GROUP/ROLE &gt; all-subjects, then {@code priority}, then a stable id tie-break) and the first
 * one whose referenced policy is still ENABLED wins. A disabled/deleted binding is transparent — it does
 * NOT collapse resolution to the fallback, so a disabled strict binding can never silently mask an enabled
 * stricter one below it. With no usable binding it falls back to the user's global session resolution
 * ({@link SessionPolicyService#resolveForUser}) / empty for auth so the caller applies its own fallback.
 *
 * <p>SECURITY INVARIANT (enforce when wiring consumers): this MUST run inside the login user's bound org
 * context ({@code callInOrg(loginOrg)}), never as platform. Org confinement of a binding rides RLS, and a
 * USER-subject id is a GLOBAL identity — resolving in a platform/no-org context would let another tenant's
 * {@code USER→policy} binding on a shared app match this user.
 */
@Service
@RequiredArgsConstructor
public class PolicyBindingResolver {

    private final PolicyBindingRepository bindings;
    private final UserGroupService userGroups;
    private final AuthPolicyResolver authPolicies;
    private final SessionPolicyService sessionPolicies;

    /** The auth policy bound for this user in this app, or empty (caller applies login/step-up fallback). */
    @Transactional(readOnly = true)
    public Optional<AuthPolicyView> resolveAuthPolicy(UserAccount user, AppType appType, String appId) {
        return resolveField(user, appType, appId, PolicyBinding::getAuthPolicyId,
                id -> authPolicies.highestPriorityEnabled(List.of(id)));
    }

    /** The session policy bound for this user in this app, else the user's global session policy / Default. */
    @Transactional(readOnly = true)
    public SessionPolicyDetails resolveSessionPolicy(UserAccount user, AppType appType, String appId) {
        return resolveField(user, appType, appId, PolicyBinding::getSessionPolicyId,
                id -> sessionPolicies.findById(id).filter(SessionPolicyDetails::isEnabled))
                .orElseGet(() -> sessionPolicies.resolveForUser(user));
    }

    /**
     * Walks the matching bindings most-specific first and returns the first whose {@code field} policy
     * {@code load}s to an enabled policy — so a disabled higher-specificity binding is skipped rather than
     * shadowing an enabled lower-specificity one.
     */
    private <P> Optional<P> resolveField(UserAccount user, AppType appType, String appId,
            Function<PolicyBinding, UUID> field, Function<UUID, Optional<P>> load) {
        UUID userId = user.getId();
        Set<UUID> roleIds = user.getRoles().stream().map(RoleRef::getId).collect(Collectors.toSet());
        Set<UUID> groupIds = new HashSet<>(userGroups.groupIdsOf(userId));
        return bindings.findByAppTypeAndAppId(appType, appId).stream()
                .filter(b -> field.apply(b) != null)
                .filter(b -> subjectMatches(b, userId, roleIds, groupIds))
                .sorted(Comparator.comparingInt(this::specificity)
                        .thenComparingInt(PolicyBinding::getPriority)
                        .thenComparing(PolicyBinding::getId)
                        .reversed())
                .map(b -> load.apply(field.apply(b)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** USER is the most specific, then a role/group membership, then the app-wide (all-subjects) default. */
    private int specificity(PolicyBinding b) {
        if (b.getSubjectType() == null) {
            return 1;
        }
        return b.getSubjectType() == PolicyBinding.SubjectType.USER ? 3 : 2;
    }

    private boolean subjectMatches(PolicyBinding b, UUID userId, Set<UUID> roleIds, Set<UUID> groupIds) {
        if (b.getSubjectType() == null) {
            return true;
        }
        return switch (b.getSubjectType()) {
            case USER -> b.getSubjectId().equals(userId);
            case ROLE -> roleIds.contains(b.getSubjectId());
            case GROUP -> groupIds.contains(b.getSubjectId());
        };
    }
}
