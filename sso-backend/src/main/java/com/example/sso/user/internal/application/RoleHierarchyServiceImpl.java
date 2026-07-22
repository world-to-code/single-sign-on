package com.example.sso.user.internal.application;

import com.example.sso.user.role.RoleHierarchyService;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.internal.role.domain.UserRoleRepository;
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
    private final RoleRepository roles;

    @Override
    @Transactional(readOnly = true)
    public boolean actorMayManageRole(UUID actorUserId, UUID targetRoleId) {
        // Fail-closed on a role that does not resolve, matching the by-name variant below. "Not above my apex"
        // is trivially TRUE for an id that names nothing here — a typo, or a role belonging to another tenant
        // that RLS hides — so without this the dominance term said yes about a role it could not see, and the
        // permission terms that follow it read an empty set and passed vacuously too.
        return targetRoleId != null
                && roles.findById(targetRoleId).isPresent()
                && !rolesAboveApex(actorUserId).contains(targetRoleId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean actorMayManageRoleName(UUID actorUserId, String roleName, UUID actingOrg) {
        return roleTierResolver.resolve(roleName, actingOrg)
                .map(role -> !rolesAboveApex(actorUserId).contains(role.getId()))
                .orElse(false); // fail-closed: an unknown/unresolved name is never manageable
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> rolesAboveActor(UUID actorUserId) {
        return rolesAboveApex(actorUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> apexRolesOf(UUID actorUserId) {
        return apexRoleIds(actorUserId);
    }

    /**
     * The roles strictly ABOVE the actor: the ancestors of the actor's APEX (highest-held) roles. Reducing to
     * the apex first is what makes this correct when an actor holds a role REDUNDANTLY — e.g. ROLE_USER
     * alongside ROLE_ORG_ADMIN. The actor's position is their highest role, so only what is above THAT is
     * above them; the low role's own ancestors (which include the actor's own higher roles, and — in a
     * diamond — a sibling of a higher role) are correctly not counted. Ancestors of a maximal set never loop
     * back to a reachable role, so no further subtraction is needed.
     */
    private Set<UUID> rolesAboveApex(UUID actorUserId) {
        return new HashSet<>(roleClosure.ancestors(apexRoleIds(actorUserId)));
    }

    /** The actor's APEX roles: their held roles minus any that another held role already dominates. */
    private Set<UUID> apexRoleIds(UUID actorUserId) {
        Set<UUID> held = heldRoleIds(actorUserId);
        held.removeAll(roleClosure.descendants(held));
        return held;
    }

    private Set<UUID> heldRoleIds(UUID actorUserId) {
        Set<UUID> held = new HashSet<>(userRoles.findRoleIdsByUserId(actorUserId));
        held.addAll(userGroups.findDelegatedRoleIdsForMember(actorUserId));
        return held;
    }
}
