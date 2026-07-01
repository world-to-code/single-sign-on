package com.example.sso.scim.internal.application;

import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserAccount;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.ConflictException;
import de.captaingoldfish.scim.sdk.common.exceptions.ResourceNotFoundException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import de.captaingoldfish.scim.sdk.server.response.PartialListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Transactional persistence for the SCIM Group endpoint, mapped onto domain roles (membership = users'
 * role assignments). Delegates all role/membership state to the user module ({@link RoleService}) — it
 * never touches the {@code Role}/{@code AppUser} entities.
 */
@Service
@RequiredArgsConstructor
public class ScimGroupService {

    private final RoleService roleService;

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
        if (roleService.findByName(displayName).isPresent()) {
            throw new ConflictException("group already exists: " + displayName);
        }
        RoleRef role = roleService.create(displayName);
        roleService.setMembers(role.getId(), desiredMembers(resource));
        return ScimGroupMapper.toScim(role, roleService.members(role.getId()));
    }

    @Transactional(readOnly = true)
    public Group get(String id) {
        RoleRef role = roleService.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + id));
        return ScimGroupMapper.toScim(role, roleService.members(role.getId()));
    }

    @Transactional(readOnly = true)
    public PartialListResponse<Group> list(long startIndex, int count) {
        long total = roleService.count();
        List<RoleRef> rolePage = roleService.page(startIndex, count);
        Map<UUID, List<UserAccount>> membersByRole = roleService.membersByRoleIds(
                rolePage.stream().map(RoleRef::getId).collect(Collectors.toSet()));
        List<Group> page = rolePage.stream()
                .map(role -> ScimGroupMapper.toScim(role, membersByRole.getOrDefault(role.getId(), List.of())))
                .toList();
        return PartialListResponse.<Group>builder().resources(page).totalResults(total).build();
    }

    @Transactional
    public Group update(Group resource) {
        RoleRef role = roleService.findById(parseId(resource.getId()
                        .orElseThrow(() -> new ResourceNotFoundException("missing id on update"))))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        ensureManageable(role.getName());
        roleService.setMembers(role.getId(), desiredMembers(resource));
        return ScimGroupMapper.toScim(role, roleService.members(role.getId()));
    }

    @Transactional
    public void delete(String id) {
        RoleRef role = roleService.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + id));
        ensureManageable(role.getName());
        roleService.delete(role.getId());
    }

    private static Set<UUID> desiredMembers(Group group) {
        return group.getMembers().stream()
                .map(Member::getValue)
                .flatMap(Optional::stream)
                .map(ScimGroupService::parseId)
                .collect(Collectors.toSet());
    }

    private static UUID parseId(String id) {
        return ScimSupport.parseId(id);
    }
}
