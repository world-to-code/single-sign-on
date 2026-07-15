package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.group.UserGroupService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Assigns matching users to a GROUP's membership. The group must be in the acting tier and not a system group. */
@Component
@RequiredArgsConstructor
class GroupTargetApplier implements MappingTargetApplier {

    private final UserGroupService groups;
    private final OrgTierGuard tierGuard;

    @Override
    public MappingTargetKind kind() {
        return MappingTargetKind.GROUP;
    }

    @Override
    public String validateInTier(UUID targetId) {
        GroupView group;
        try {
            group = groups.get(targetId);
        } catch (NotFoundException e) {
            throw BadRequestException.of("mapping.rule.groupUnknown");
        }
        if (!Objects.equals(groups.orgIdOf(targetId).orElse(null), tierGuard.currentTier())) {
            throw BadRequestException.of("mapping.rule.groupNotInTier");
        }
        if (group.system()) {
            throw BadRequestException.of("mapping.rule.groupSystem");
        }
        return group.name();
    }

    @Override
    public String label(UUID targetId) {
        return groups.idNames(List.of(targetId)).stream().findFirst().map(IdName::getName).orElse(null);
    }

    @Override
    public void assign(UUID targetId, UUID userId) {
        groups.addMember(targetId, userId);
    }

    @Override
    public void assignAll(UUID targetId, Set<UUID> userIds) {
        groups.addMembers(targetId, userIds);
    }

    @Override
    public void unassign(UUID targetId, UUID userId) {
        groups.removeMember(targetId, userId);
    }
}
