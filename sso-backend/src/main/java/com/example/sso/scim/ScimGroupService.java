package com.example.sso.scim;

import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import com.example.sso.user.Role;
import com.example.sso.user.RoleRepository;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.ConflictException;
import de.captaingoldfish.scim.sdk.common.exceptions.ResourceNotFoundException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import de.captaingoldfish.scim.sdk.server.response.PartialListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transactional persistence for the SCIM Group endpoint, mapped onto domain {@link Role}s
 * (membership = users' role assignments).
 */
@Service
@RequiredArgsConstructor
public class ScimGroupService {

    private final RoleRepository roles;
    private final AppUserRepository users;

    /**
     * Roles that confer elevated privilege and must never be created/assigned/deleted via SCIM. This
     * guard is name-based, which is adequate today because privileges are granted per-user (not via
     * roles), so {@code ROLE_ADMIN} is the only role carrying elevated reach. If privileged CUSTOM
     * roles are ever introduced (privileges attached to a role), switch this to protect by effective
     * privilege rather than by role name.
     */
    private static final Set<String> PROTECTED_ROLES = Set.of("ROLE_ADMIN");

    private static void ensureManageable(String roleName) {
        if (PROTECTED_ROLES.contains(roleName)) {
            throw new BadRequestException("group '" + roleName + "' is privileged and cannot be managed via SCIM");
        }
    }

    @Transactional
    public Group create(Group resource) {
        String displayName = resource.getDisplayName()
                .orElseThrow(() -> new BadRequestException("displayName is required"));
        ensureManageable(displayName);
        if (roles.findByName(displayName).isPresent()) {
            throw new ConflictException("group already exists: " + displayName);
        }
        Role role = roles.save(new Role(displayName));
        reconcileMembers(role, resource);
        return ScimGroupMapper.toScim(role, users.findByRoles_Id(role.getId()));
    }

    @Transactional(readOnly = true)
    public Group get(String id) {
        Role role = roles.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + id));
        return ScimGroupMapper.toScim(role, users.findByRoles_Id(role.getId()));
    }

    @Transactional(readOnly = true)
    public PartialListResponse<Group> list(long startIndex, int count) {
        long total = roles.count();
        List<Role> rolePage = count <= 0 ? List.of()
                : roles.findAll(ScimSupport.pageable(startIndex, count)).getContent();
        Map<UUID, List<AppUser>> membersByRole = membersByRole(rolePage);
        List<Group> page = rolePage.stream()
                .map(role -> ScimGroupMapper.toScim(role, membersByRole.getOrDefault(role.getId(), List.of())))
                .toList();
        return PartialListResponse.<Group>builder().resources(page).totalResults(total).build();
    }

    /** Members of every role on the page, resolved in a single query (avoids a user-query per group). */
    private Map<UUID, List<AppUser>> membersByRole(List<Role> rolePage) {
        if (rolePage.isEmpty()) {
            return Map.of();
        }
        Set<UUID> roleIds = rolePage.stream().map(Role::getId).collect(Collectors.toSet());
        Map<UUID, List<AppUser>> byRole = new HashMap<>();
        for (Object[] row : users.findMembersByRoleIdIn(roleIds)) {
            byRole.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add((AppUser) row[1]);
        }
        return byRole;
    }

    @Transactional
    public Group update(Group resource) {
        Role role = roles.findById(parseId(resource.getId()
                        .orElseThrow(() -> new ResourceNotFoundException("missing id on update"))))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        ensureManageable(role.getName());
        reconcileMembers(role, resource);
        return ScimGroupMapper.toScim(role, users.findByRoles_Id(role.getId()));
    }

    @Transactional
    public void delete(String id) {
        Role role = roles.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + id));
        ensureManageable(role.getName());
        roles.delete(role);
    }

    private void reconcileMembers(Role role, Group group) {
        Set<UUID> desired = group.getMembers().stream()
                .map(Member::getValue)
                .flatMap(Optional::stream)
                .map(ScimGroupService::parseId)
                .collect(Collectors.toSet());
        List<AppUser> current = users.findByRoles_Id(role.getId());
        Set<UUID> currentIds = current.stream().map(AppUser::getId).collect(Collectors.toSet());

        // Managed entities inside this @Transactional method — dirty checking flushes; no explicit saves.
        current.stream().filter(u -> !desired.contains(u.getId())).forEach(u -> u.removeRole(role));
        Set<UUID> toAdd = desired.stream().filter(id -> !currentIds.contains(id)).collect(Collectors.toSet());
        users.findAllById(toAdd).forEach(u -> u.addRole(role));
    }

    private static UUID parseId(String id) {
        return ScimSupport.parseId(id);
    }
}
