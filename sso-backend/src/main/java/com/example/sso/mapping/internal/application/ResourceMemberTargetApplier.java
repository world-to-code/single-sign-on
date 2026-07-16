package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.resource.catalog.ResourceMembershipService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adds matching users as members of a RESOURCE. The resource must exist and be in the acting tier (its own or a
 * global one, never another tenant's); the actor's authority to add members is enforced upstream (the admin
 * layer's {@code mayAssignTarget} → {@code resourceAuth.canManage}, by id). The membership port keeps the
 * same-org / type-allows-USER integrity, so this applier only drives the add/remove. No batch add exists, so a
 * cohort is added one user at a time.
 */
@Component
@RequiredArgsConstructor
class ResourceMemberTargetApplier implements MappingTargetApplier {

    private final ResourceMembershipService resources;
    private final OrgTierGuard tierGuard;

    @Override
    public MappingTargetKind kind() {
        return MappingTargetKind.RESOURCE_MEMBER;
    }

    @Override
    public String validateInTier(UUID targetId) {
        String name = resources.nameOf(targetId)
                .orElseThrow(() -> BadRequestException.of("mapping.rule.resourceUnknown"));
        UUID resourceOrg = resources.orgIdOf(targetId).orElse(null);
        if (resourceOrg != null && !Objects.equals(resourceOrg, tierGuard.currentTier())) {
            throw BadRequestException.of("mapping.rule.resourceNotInTier"); // a global or own-tier resource only
        }
        return name;
    }

    @Override
    public String label(UUID targetId) {
        return resources.nameOf(targetId).orElse(null);
    }

    @Override
    public void assign(UUID targetId, UUID userId) {
        resources.addUser(targetId, userId);
    }

    @Override
    public void assignAll(UUID targetId, Set<UUID> userIds) {
        userIds.forEach(userId -> resources.addUser(targetId, userId));
    }

    @Override
    public void unassign(UUID targetId, UUID userId) {
        resources.removeUser(targetId, userId);
    }
}
