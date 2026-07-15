package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Grants a ROLE to matching users. The role must be assignable in the acting tier (its own or a global role,
 * never another tenant's); the actor's authority to grant it is enforced upstream (the admin layer's dominance /
 * grant-only-what-you-hold check). RoleService has no batch grant, so a cohort is granted one user at a time.
 */
@Component
@RequiredArgsConstructor
class RoleTargetApplier implements MappingTargetApplier {

    private final RoleService roles;
    private final OrgTierGuard tierGuard;

    @Override
    public MappingTargetKind kind() {
        return MappingTargetKind.ROLE;
    }

    @Override
    public String validateInTier(UUID targetId) {
        RoleRef role = roles.findById(targetId).orElseThrow(() -> BadRequestException.of("mapping.rule.roleUnknown"));
        UUID roleOrg = roles.orgIdOf(targetId).orElse(null);
        if (roleOrg != null && !Objects.equals(roleOrg, tierGuard.currentTier())) {
            throw BadRequestException.of("mapping.rule.roleNotInTier"); // a global or own-tier role only
        }
        return role.getName();
    }

    @Override
    public String label(UUID targetId) {
        return roles.findById(targetId).map(RoleRef::getName).orElse(null);
    }

    @Override
    public void assign(UUID targetId, UUID userId) {
        roles.addMember(targetId, userId);
    }

    @Override
    public void assignAll(UUID targetId, Set<UUID> userIds) {
        userIds.forEach(userId -> roles.addMember(targetId, userId));
    }

    @Override
    public void unassign(UUID targetId, UUID userId) {
        roles.removeMember(targetId, userId);
    }
}
