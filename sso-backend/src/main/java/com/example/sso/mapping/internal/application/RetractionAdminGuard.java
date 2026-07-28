package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.LastAdminRetractionGuard;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.group.UserGroupService;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Keeps an ABAC retraction from taking a tier's last administrator.
 *
 * <p>A retraction reaches {@code RoleService.removeMember} directly — the domain service, below every guard the
 * console path goes through — so a tenant whose {@code ROLE_ORG_ADMIN} is conferred by a mapping rule could
 * lose its last administrator to an attribute edit and nothing else in the system would notice.
 *
 * <p>The whole difficulty is temporal, which is why this hands out a {@link RetractionScope} rather than a
 * boolean: the question has to be asked before the memberships are gone, and answered after. Call
 * {@link #before} at the top of the transaction and spend the scope at the bottom.
 */
@Component
@RequiredArgsConstructor
class RetractionAdminGuard {

    private final LastAdminRetractionGuard invariant;
    private final UserGroupService userGroups;
    private final OrgTierGuard tierGuard;
    private final MappingAuditTrail trail;

    /** Read BEFORE anything is retracted. {@code candidates} are the rules whose claims could be dropped. */
    RetractionScope before(Collection<MappingRule> candidates) {
        UUID tier = tierGuard.currentTier();
        return new RetractionScope(this, tier,
                invariant.retractionWouldNeedGuarding(rolesBehind(privilegeTargetsOf(candidates)), tier));
    }

    /** The recount itself, plus the one audit row that has to outlive the rollback it is about. */
    void recount(UUID tier, int retractedTargets) {
        try {
            invariant.ensureRetractionRetainsAdmin(tier);
        } catch (RuntimeException refused) {
            trail.refusalNow(tier, retractedTargets, refused);
            throw refused;
        }
    }

    /** The targets whose loss could take authority away — a RESOURCE_MEMBER rule confers none. */
    private Set<UUID> privilegeTargetsOf(Collection<MappingRule> candidates) {
        return candidates.stream()
                .filter(rule -> rule.getThenKind() != MappingTargetKind.RESOURCE_MEMBER)
                .map(MappingRule::getTargetId)
                .collect(Collectors.toSet());
    }

    /** What a retracted target actually takes away: a ROLE is itself, a GROUP is the roles it delegates. */
    private Set<UUID> rolesBehind(Collection<UUID> targetIds) {
        if (targetIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> roleIds = new HashSet<>(targetIds);
        userGroups.delegatedRoleIds(targetIds).values().forEach(roleIds::addAll);
        return roleIds;
    }
}
