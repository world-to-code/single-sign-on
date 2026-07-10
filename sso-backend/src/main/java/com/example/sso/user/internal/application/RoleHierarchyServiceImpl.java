package com.example.sso.user.internal.application;

import com.example.sso.user.RoleHierarchyService;
import com.example.sso.user.internal.domain.UserGroupRepository;
import com.example.sso.user.internal.domain.UserRoleRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link RoleHierarchyService}. Position is derived from the actor's HELD role ids (direct
 * assignments + group-delegated) walked over the inheritance DAG via {@link RoleClosure}. Every method is
 * transactional and read-only so the walk runs on a connection carrying the ambient {@code OrgContext} as
 * the RLS GUCs — the acting tier the admin request is bound to — exactly like the rest of the admin authz
 * reads. Name resolution reuses {@link RoleTierResolver} so the role that is CHECKED is the role that gets
 * ASSIGNED.
 */
@Service
@RequiredArgsConstructor
class RoleHierarchyServiceImpl implements RoleHierarchyService {

    private final UserRoleRepository userRoles;
    private final UserGroupRepository userGroups;
    private final RoleClosure roleClosure;
    private final RoleTierResolver roleTierResolver;

    @Override
    @Transactional(readOnly = true)
    public boolean actorDominatesRole(UUID actorUserId, UUID targetRoleId) {
        return targetRoleId != null && roleClosure.descendants(heldRoleIds(actorUserId)).contains(targetRoleId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean actorDominatesRoleName(UUID actorUserId, String roleName, UUID actingOrg) {
        return roleTierResolver.resolve(roleName, actingOrg)
                .map(role -> roleClosure.descendants(heldRoleIds(actorUserId)).contains(role.getId()))
                .orElse(false); // fail-closed: an unknown/unresolved name is never dominated (never assignable)
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> rolesAboveActor(UUID actorUserId) {
        return roleClosure.ancestors(heldRoleIds(actorUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> apexRolesOf(UUID actorUserId) {
        Set<UUID> held = heldRoleIds(actorUserId);
        held.removeAll(roleClosure.descendants(held)); // drop any held role dominated by another held role
        return held;
    }

    private Set<UUID> heldRoleIds(UUID actorUserId) {
        Set<UUID> held = new HashSet<>(userRoles.findRoleIdsByUserId(actorUserId));
        held.addAll(userGroups.findDelegatedRoleIdsForMember(actorUserId));
        return held;
    }
}
