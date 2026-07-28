package com.example.sso.scim.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.Roles;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.account.UserAccount;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.ConflictException;
import de.captaingoldfish.scim.sdk.common.exceptions.ForbiddenException;
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
 *
 * <p>Roles are GLOBAL/system + per-org, and the {@code role} RLS policy makes global rows visible in ANY
 * tenant context, while {@code app_user_role} is non-RLS. A tenant-bound SCIM token must therefore NOT reach
 * this endpoint at all — otherwise it could enumerate every user (via a global role's members) or delete a
 * global system role platform-wide. Group management is a PLATFORM (global-token) operation only; a
 * tenant-bound token is refused here (403). Tenant SCIM provisioning is the {@code /Users} endpoint, which
 * is membership-isolated ({@code ScimUserService}).
 */
@Service
@RequiredArgsConstructor
public class ScimGroupService {

    private final RoleService roleService;
    private final OrgContext orgContext;
    private final ScimMembershipDenyAuditor denyAuditor;

    /**
     * Roles that confer elevated privilege and must never be created/assigned/deleted via SCIM. This
     * guard is name-based, which is adequate today because privileges are granted per-user (not via
     * roles), so {@code ROLE_ADMIN} and the delegated {@code ROLE_GROUP_ADMIN} are the only roles
     * carrying elevated reach. If privileged CUSTOM roles are ever introduced (privileges attached to
     * a role), switch this to protect by effective
     * privilege rather than by role name.
     */
    private static final Set<String> PROTECTED_ROLES = Set.of(Roles.ADMIN, Roles.GROUP_ADMIN);

    private void ensureManageable(String roleName) {
        if (PROTECTED_ROLES.contains(roleName)) {
            throw new BadRequestException("group '" + roleName + "' is privileged and cannot be managed via SCIM");
        }
    }

    /** Group management is platform-only: a tenant-bound token (which could reach global roles via RLS) is 403'd. */
    private void requireGlobalToken() {
        if (orgContext.currentOrg().isPresent()) {
            throw new ForbiddenException("SCIM group management requires a platform token");
        }
    }

    @Transactional
    public Group create(Group resource) {
        requireGlobalToken();
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
        requireGlobalToken();
        RoleRef role = roleService.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + id));
        return ScimGroupMapper.toScim(role, roleService.members(role.getId()));
    }

    @Transactional(readOnly = true)
    public PartialListResponse<Group> list(long startIndex, int count) {
        requireGlobalToken();
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
        requireGlobalToken();
        RoleRef role = roleService.findById(parseId(resource.getId()
                        .orElseThrow(() -> new ResourceNotFoundException("missing id on update"))))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        ensureManageable(role.getName());
        if (role.isSystem()) { // symmetric with delete: no destructive membership rewrite of a system role
            throw new BadRequestException("system group '" + role.getName() + "' cannot be modified via SCIM");
        }
        Set<UUID> desired = desiredMembers(resource);
        Set<UUID> losing = membersLost(role, desired); // read BEFORE the write; afterwards they are simply absent
        roleService.setMembers(role.getId(), desired);
        denyAuditor.noteLiftedBy("SCIM group update", role.getId(), role.getName(), losing);

        return ScimGroupMapper.toScim(role, roleService.members(role.getId()));
    }

    /**
     * Who this replace takes off the role. Id-only: the caller needs identities, not accounts, and a directory
     * sync can name tens of thousands of members — materializing each of them to read one field is a cost the
     * audit line does not justify.
     */
    private Set<UUID> membersLost(RoleRef role, Set<UUID> desired) {
        return roleService.memberIds(role.getId()).stream()
                .filter(memberId -> !desired.contains(memberId))
                .collect(Collectors.toSet());
    }

    @Transactional
    public void delete(String id) {
        requireGlobalToken();
        RoleRef role = roleService.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Group not found: " + id));
        ensureManageable(role.getName());

        // Deleting the role drops every membership AND the subject the deny resolved against, so it lifts for
        // everyone at once — the widest version of the act the update above notes. Read the members first, and
        // note the lift only once the delete has actually happened: deleteRole below refuses EVERY system role,
        // and the audit row commits in its own transaction, so noting it first would assert a lift that a 409
        // then undid.
        Set<UUID> losing = membersLost(role, Set.of());

        // deleteRole (not the unguarded delete) so EVERY system role — ROLE_USER, ROLE_ORG_ADMIN, … — is
        // 409-protected, not just the name-listed PROTECTED_ROLES.
        roleService.deleteRole(role.getId());
        denyAuditor.noteLiftedBy("SCIM group delete", role.getId(), role.getName(), losing);
    }

    private Set<UUID> desiredMembers(Group group) {
        return group.getMembers().stream()
                .map(Member::getValue)
                .flatMap(Optional::stream)
                .map(this::parseId)
                .collect(Collectors.toSet());
    }

    private UUID parseId(String id) {
        return ScimSupport.parseId(id);
    }
}
