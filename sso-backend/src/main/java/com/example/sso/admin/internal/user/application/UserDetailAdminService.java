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
import com.example.sso.user.group.GroupMembership;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import com.example.sso.webauthn.PasskeyService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

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

        return UserDetailView.of(user, roleAssignments(user, memberships),
                user.getDirectPermissionNames().stream().sorted().toList(),
                effectivePermissions(user, memberships));
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

    /** All permissions the user effectively holds: role + group-role + direct, read-implication expanded. */
    private List<String> effectivePermissions(UserAccount user, List<GroupMembership> memberships) {
        Set<String> permissions = new HashSet<>();
        user.getRoles().forEach(role -> permissions.addAll(role.getPermissionNames()));
        memberships.forEach(membership -> membership.roles()
                .forEach(role -> permissions.addAll(role.getPermissionNames())));
        permissions.addAll(user.getDirectPermissionNames());

        return Permissions.expandImplied(permissions).stream().sorted().toList();
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
