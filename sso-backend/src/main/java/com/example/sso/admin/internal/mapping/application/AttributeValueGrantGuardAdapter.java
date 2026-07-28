package com.example.sso.admin.internal.mapping.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.mapping.MappingRuleService;
import com.example.sso.mapping.MappingTarget;
import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of {@link AttributeValueGrantGuard}: bounds a key by what its values confer.
 *
 * <p>The ceiling is {@link AdminAccessPolicy#mayAssignTarget(com.example.sso.mapping.MappingTargetKind,
 * java.util.UUID)} — the same one the manual grant and the group-conferral path enforce, resolved by target
 * ID. It fails closed on an unresolved actor, which is why nothing here checks for one separately.
 *
 * <p>Verdicts are memoized per target: one key is commonly read by several rules pointing at the same role,
 * and a write names several keys at once, so the naive shape re-resolves the same ceiling repeatedly.
 */
@Component
@RequiredArgsConstructor
class AttributeValueGrantGuardAdapter implements AttributeValueGrantGuard {

    private final MappingRuleService rules;
    private final AdminAccessPolicy accessPolicy;
    private final DenyService denies;
    private final UserGroupService userGroups;

    @Override
    public Set<String> keysBeyondAuthority(Collection<String> attrKeys) {
        return beyond(rules.privilegeTargetsByKey(attrKeys));
    }

    @Override
    public Set<String> keysWhoseRemovalLiftsDeny(Collection<String> attrKeys) {
        Map<String, Set<MappingTarget>> decided = rules.privilegeTargetsByKey(attrKeys);
        if (decided.isEmpty()) {
            return Set.of();
        }
        // Memoized per target for the reason the grant side gives: one write names several keys, and several
        // rules commonly point at the same group.
        Map<MappingTarget, Boolean> verdicts = new HashMap<>();
        Set<String> beyond = new LinkedHashSet<>();
        for (Map.Entry<String, Set<MappingTarget>> entry : decided.entrySet()) {
            if (!entry.getValue().stream().allMatch(target -> verdicts.computeIfAbsent(target, this::mayUnbind))) {
                beyond.add(entry.getKey());
            }
        }
        return beyond;
    }

    /**
     * Whether losing this target would only lose denies the actor could have lifted anyway.
     *
     * <p>A group is asked about its OWN denies and about the denies on every role it delegates — the grant
     * side already treats a group as "a role grant wearing a different name" ({@code mayConferRolesOf}), and
     * the deny side has to match. A deny resolves against the holder's APEX roles, so losing the group loses
     * the delegated role and the deny keyed on it: asking only about the group let a removal lift a deny by
     * one indirection, which is the escalation this ceiling exists to refuse.
     *
     * <p>The switch is exhaustive on purpose. A {@code default} branch here would return "removable" for a
     * mapping target kind added later — and the upstream filter that excludes {@code RESOURCE_MEMBER} today
     * would be the only thing standing in for this check. Fail to compile instead.
     */
    private boolean mayUnbind(MappingTarget target) {
        return switch (target.kind()) {
            case GROUP -> denies.mayLiftEveryDenyOn(DenySubjectKind.GROUP, target.targetId())
                    && delegatedRolesCarryNoUnliftableDeny(target.targetId());
            case ROLE -> denies.mayLiftEveryDenyOn(DenySubjectKind.ROLE, target.targetId());
            // A resource membership confers no authority, so no deny can ride on losing it. Excluded upstream
            // as well; named here so a new kind cannot slip through as "removable".
            case RESOURCE_MEMBER -> true;
        };
    }

    private boolean delegatedRolesCarryNoUnliftableDeny(UUID groupId) {
        return userGroups.delegatedRoleIds(Set.of(groupId)).getOrDefault(groupId, Set.of()).stream()
                .allMatch(roleId -> denies.mayLiftEveryDenyOn(DenySubjectKind.ROLE, roleId));
    }

    private Set<String> beyond(Map<String, Set<MappingTarget>> decided) {
        if (decided.isEmpty()) {
            return Set.of();
        }
        Map<MappingTarget, Boolean> verdicts = new HashMap<>();
        Set<String> beyond = new LinkedHashSet<>();
        for (Map.Entry<String, Set<MappingTarget>> entry : decided.entrySet()) {
            if (!mayConferAll(entry.getValue(), verdicts)) {
                beyond.add(entry.getKey());
            }
        }
        return beyond;
    }

    private boolean mayConferAll(Set<MappingTarget> targets, Map<MappingTarget, Boolean> verdicts) {
        return targets.stream().allMatch(target -> verdicts.computeIfAbsent(target,
                each -> accessPolicy.mayAssignTarget(each.kind(), each.targetId())));
    }
}
