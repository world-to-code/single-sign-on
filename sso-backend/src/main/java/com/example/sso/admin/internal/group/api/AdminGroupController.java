package com.example.sso.admin.internal.group.api;

import com.example.sso.admin.internal.shared.api.RequestIds;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.security.CanAssignGroupRoles;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.ApplicationView;
import com.example.sso.user.GroupMembersPage;
import com.example.sso.user.GroupRequest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.GroupView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserGroupService;
import com.example.sso.portal.ApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the organizational directory: groups, their paginated members and assigned apps,
 * and the group→role / group→manager delegation edited from the group detail page.
 */
@RestController
@RequestMapping("/api/admin/groups")
@RequiredArgsConstructor
public class AdminGroupController {

    private final UserGroupService userGroups;
    private final ApplicationService applications;
    private final AdminAuditLogger auditLogger;

    @GetMapping
    @RequirePermission(Permissions.GROUP_READ)
    public List<GroupView> groups() {
        return userGroups.listAll();
    }

    @PostMapping
    @RequirePermission(Permissions.GROUP_CREATE)
    public ResponseEntity<GroupView> createGroup(@Valid @RequestBody GroupRequest request) {
        GroupView created = userGroups.create(new GroupSpec(request.name(), request.description(),
                request.externalId(), RequestIds.toUuidSet(request.memberUserIds())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.GROUP_UPDATE)
    public GroupView updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest request) {
        return userGroups.update(id, new GroupSpec(request.name(), request.description(),
                request.externalId(), RequestIds.toUuidSet(request.memberUserIds())));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.GROUP_DELETE)
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        userGroups.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Replaces the roles delegated to a group; members inherit them. */
    @PutMapping("/{id}/roles")
    @CanAssignGroupRoles
    public GroupView setGroupRoles(@PathVariable UUID id, @RequestBody SetGroupRolesRequest request) {
        Set<String> roleNames = Objects.requireNonNullElseGet(request.roleNames(), Set::of);
        GroupView view = userGroups.setRoles(id, roleNames);
        auditLogger.log(AuditType.GROUP_ROLES_UPDATED, "group=" + id + " roles=" + roleNames);
        return view;
    }

    /** Replaces the group's managers (scoped admins allowed to manage its members). */
    @PutMapping("/{id}/managers")
    @RequirePermission(Permissions.GROUP_UPDATE)
    public GroupView setGroupManagers(@PathVariable UUID id, @RequestBody SetGroupManagersRequest request) {
        GroupView view = userGroups.setManagers(id, RequestIds.toUuidSet(request.managerUserIds()));
        auditLogger.log(AuditType.GROUP_MANAGERS_UPDATED, "group=" + id + " managers=" + request.managerUserIds());
        return view;
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.GROUP_READ)
    public GroupView group(@PathVariable UUID id) {
        return userGroups.get(id);
    }

    @GetMapping("/{id}/members")
    @RequirePermission(Permissions.GROUP_READ)
    public GroupMembersPage groupMembers(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return userGroups.members(id, page, size);
    }

    @GetMapping("/{id}/applications")
    @RequirePermission(Permissions.GROUP_READ)
    public List<ApplicationView> groupApplications(@PathVariable UUID id) {
        return applications.appsForGroup(id);
    }

    @GetMapping("/search")
    @RequirePermission(Permissions.GROUP_READ)
    public List<Suggestion> searchGroups(@RequestParam(name = "q", defaultValue = "") String q,
                                         @RequestParam(defaultValue = "20") int limit) {
        return userGroups.search(q, limit);
    }
}
