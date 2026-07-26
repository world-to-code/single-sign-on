package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.audit.application.AuditAccessPolicy;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.group.GroupMembership;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.webauthn.PasskeyService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only secondary sections of the admin user-detail page: the applications a user can launch, their
 * enrolled authentication devices, active sessions, and recent activity. Aggregates the owning modules'
 * public services and returns admin DTOs — no entity crosses a boundary, and the real session id is
 * never exposed (only the opaque public handle).
 */
@Service
@RequiredArgsConstructor
public class UserDetailAdminService {

    private final UserService userService;
    private final DenyService denyService;
    private final UserGroupService userGroups;
    private final ApplicationService applications;
    private final PasskeyService passkeys;
    private final MfaService mfaService;
    private final SessionMetadataStore sessionMetadata;
    private final UserSessions userSessions;
    private final AuditService audit;
    private final AuditAccessPolicy auditAccessPolicy;

    /**
     * Full detail for a single user, with roles attributed to their source and effective permissions.
     *
     * <p>Here rather than on the admin service it came from: this class already exists to answer "everything
     * about one user", and that was the only place needing the group service — a collaborator carried by a
     * larger class for one method.
     */
    @Transactional(readOnly = true)
    public UserDetailView getUser(UUID id) {
        UserAccount user = userService.findById(id).orElseThrow(() -> NotFoundException.of("user.notFound"));
        List<GroupMembership> memberships = userGroups.membershipsForUser(id);

        // GRANTED = what role+group+direct hand out; EFFECTIVE = the deny-applied resolved authorities. A
        // permission granted but missing from effective was removed by a deny — the "why absent" answer.
        Set<String> granted = grantedPermissions(user, memberships);
        Set<String> effective = effectivePermissions(id);
        List<String> denied = granted.stream().filter(permission -> !effective.contains(permission)).sorted().toList();

        return UserDetailView.of(user, roleAssignments(user, memberships),
                user.getDirectPermissionNames().stream().sorted().toList(),
                effective.stream().sorted().toList(), denied, denyService.userDenies(id));
    }

    /** Merges the user's direct roles with roles delegated via groups, tracking each role's source. */
    private List<RoleAssignmentView> roleAssignments(UserAccount user, List<GroupMembership> memberships) {
        Map<UUID, String> names = new LinkedHashMap<>();
        Set<UUID> directIds = new HashSet<>();
        Map<UUID, TreeSet<String>> viaGroups = new LinkedHashMap<>();

        for (RoleRef role : user.getRoles()) {
            names.put(role.getId(), role.getName());
            directIds.add(role.getId());
        }
        for (GroupMembership membership : memberships) {
            for (RoleRef role : membership.roles()) {
                names.putIfAbsent(role.getId(), role.getName());
                viaGroups.computeIfAbsent(role.getId(), key -> new TreeSet<>()).add(membership.groupName());
            }
        }

        List<RoleAssignmentView> assignments = new ArrayList<>();
        names.forEach((roleId, name) -> assignments.add(new RoleAssignmentView(roleId.toString(), name,
                directIds.contains(roleId), List.copyOf(viaGroups.getOrDefault(roleId, new TreeSet<>())))));
        assignments.sort((first, second) -> first.roleName().compareToIgnoreCase(second.roleName()));

        return assignments;
    }

    /**
     * The permissions GRANTED to the user — role + group-role + direct, with wildcard tokens expanded to their
     * members and each mutating perm implying its read. This is the pre-deny set; the caller subtracts the
     * deny-applied effective set from it to surface which grants a deny removed.
     */
    private Set<String> grantedPermissions(UserAccount user, List<GroupMembership> memberships) {
        Set<String> permissions = new HashSet<>();
        addPermissionsOf(user.getRoles(), permissions);
        for (GroupMembership membership : memberships) {
            addPermissionsOf(membership.roles(), permissions);
        }
        permissions.addAll(user.getDirectPermissionNames());

        return Permissions.expandGrants(permissions);
    }

    /**
     * The user's EFFECTIVE permissions: the resolved login authorities (deny already applied, wildcards
     * expanded, role NAMES excluded — an authority is a permission iff it is {@code resource:action} shaped).
     * Sourced from the one resolution chokepoint so the console shows exactly what the user can do.
     */
    private Set<String> effectivePermissions(UUID userId) {
        return userService.effectiveAuthorities(userId).stream()
                .filter(authority -> authority.indexOf(':') >= 0) // drop ROLE_ names; keep perms + wildcard tokens
                .collect(Collectors.toSet());
    }

    private void addPermissionsOf(Collection<? extends RoleRef> roles, Set<String> permissions) {
        for (RoleRef role : roles) {
            permissions.addAll(role.getPermissionNames());
        }
    }

    @Transactional(readOnly = true)
    public List<ApplicationView> applications(UUID userId) {
        return applications.appsForUser(require(userId));
    }

    @Transactional(readOnly = true)
    public UserDevicesView devices(UUID userId) {
        UserAccount user = require(userId);
        return new UserDevicesView(mfaService.hasEnabledTotp(userId), passkeys.list(user));
    }

    public List<UserSessionView> sessions(UUID userId) {
        UserAccount user = require(userId);
        // Scope to the target's OWN org: username is unique only within an org, so an unscoped metadata lookup
        // would expose a same-named user's session PII (IP/UA/activity) in another tenant.
        Set<String> orgSessionIds = userSessions.sessionIdsForUser(user.getUsername(), user.getOrgId());
        return sessionMetadata.forUser(user.getUsername()).stream()
                .filter(metadata -> orgSessionIds.contains(metadata.sessionId()))
                .map(UserSessionView::of)
                .toList();
    }

    public Page<AuditEntry> activity(UUID userId, int page, int size) {
        // Scope to the target's OWN org: usernames are unique only within an org (V68), so an org-less lookup
        // could surface a same-named principal's activity from another tenant.
        UserAccount user = require(userId);
        List<AuditEntry> recent = audit.recentForPrincipal(user.getOrgId(), user.getUsername());
        // Gate the actor PII exactly as the main audit console does: a viewer with user:read but without
        // audit:read:pii sees the activity without actor email/display/IP/device (the principal name remains).
        if (!auditAccessPolicy.canReadPii()) {
            recent = recent.stream().map(AuditEntry::withoutPii).toList();
        }
        return Page.of(recent, page, size);
    }

    /** Admin force-expiry: ends ALL of a user's live sessions (and, via the listeners, logs them out of
     *  their OIDC/SAML participants). Returns the number of sessions ended. */
    public int terminateSessions(UUID userId) {
        UserAccount user = require(userId);
        int count = userSessions.terminateForUser(user.getUsername(), user.getOrgId());
        audit.record(new AuditRecord(AuditType.SESSION_ADMIN_REVOKED, user.getUsername(), true,
                "count=" + count, null));
        return count;
    }

    private UserAccount require(UUID userId) {
        return userService.findById(userId).orElseThrow(() -> NotFoundException.of("user.notFound"));
    }
}
