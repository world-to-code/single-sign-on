package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingCondition;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingConditionRepository;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.rbac.Permissions;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portal's implementation of {@link AttributeKeyPolicyGuard}: reads the bindings that test the keys and asks
 * whether the acting administrator holds the permission each one's policies need.
 *
 * <p>A PERMISSION check, not the dominance check roles get — policies form no hierarchy, so there is no
 * "above" for one auth policy relative to another, and the honest question is whether the actor may manage
 * policies of that kind at all.
 *
 * <p>Runs at admission, on the request thread, so the authorities are the acting administrator's current ones
 * straight from the security context rather than anything captured earlier.
 *
 * <p>Tier-aware, because permission names are not. RLS shows a tenant the GLOBAL bindings too, and those do
 * govern its users — but the policy permissions are tenant-grantable, so an org admin holds the same NAME the
 * platform tier does while {@code OrgTierGuard} refuses them the global row. Only a binding in the actor's own
 * tier can be vouched for.
 */
@Component
@RequiredArgsConstructor
class AttributeKeyPolicyGuardImpl implements AttributeKeyPolicyGuard {

    private final PolicyBindingConditionRepository conditions;
    private final PolicyBindingRepository bindings;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public Set<String> keysBeyondAuthority(Collection<String> attrKeys) {
        if (attrKeys == null || attrKeys.isEmpty()) {
            return Set.of();
        }
        // RLS scopes both reads to the acting organization; a key nothing binds on is nobody's policy.
        Collection<PolicyBindingCondition> governing = conditions.findByAttrKeyIn(Set.copyOf(attrKeys));
        if (governing.isEmpty()) {
            return Set.of();
        }
        Set<UUID> bindingIds = governing.stream()
                .map(PolicyBindingCondition::getBindingId).collect(Collectors.toSet());
        Map<UUID, PolicyBinding> byId = bindings.findAllById(bindingIds).stream()
                .collect(Collectors.toMap(PolicyBinding::getId, Function.identity()));

        Set<String> authorities = currentAuthorities();
        UUID actingTier = orgContext.currentOrg().orElse(null);
        Set<String> beyond = new LinkedHashSet<>();
        for (PolicyBindingCondition condition : governing) {
            if (!maySet(byId.get(condition.getBindingId()), authorities, actingTier)) {
                beyond.add(condition.getAttrKey());
            }
        }
        return beyond;
    }

    /**
     * Both halves are required when a binding carries both: changing the session policy and the auth policy
     * together is two grants, and holding one is not a reason to skip the other's check.
     */
    private boolean maySet(PolicyBinding binding, Set<String> authorities, UUID actingTier) {
        if (binding == null) {
            // The condition names a binding this reader cannot see. Refuse rather than read that as "no binding".
            return false;
        }
        if (!Objects.equals(binding.getOrgId(), actingTier)) {
            // Another tier's binding. RLS deliberately shows a tenant the GLOBAL rows, and a global binding does
            // govern that tenant's users — but OrgTierGuard will not let a tenant edit one, so holding the
            // permission NAME is not authority over it. Permission names are tier-blind; tiers are not.
            return false;
        }
        return (binding.getAuthPolicyId() == null || authorities.contains(Permissions.POLICY_UPDATE))
                && (binding.getSessionPolicyId() == null
                        || authorities.contains(Permissions.SESSION_POLICY_UPDATE));
    }

    private Set<String> currentAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
