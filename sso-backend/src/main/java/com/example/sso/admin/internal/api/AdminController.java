package com.example.sso.admin.internal.api;

import com.example.sso.admin.internal.application.AdminPortalSettingsRequest;
import com.example.sso.admin.internal.application.AdminAuditLogger;
import com.example.sso.admin.internal.application.AdminService;
import com.example.sso.admin.internal.application.AdminUserView;
import com.example.sso.admin.internal.application.ClientAdminService;
import com.example.sso.admin.internal.application.ClientCreated;
import com.example.sso.admin.internal.application.ClientView;
import com.example.sso.admin.internal.application.CreateClientRequest;
import com.example.sso.admin.internal.application.CreateUserRequest;
import com.example.sso.admin.internal.application.PermissionView;
import com.example.sso.admin.internal.application.RoleView;
import com.example.sso.admin.internal.application.UpdateUserRequest;
import com.example.sso.admin.internal.application.UserAdminService;
import com.example.sso.admin.internal.application.UserDetailAdminService;
import com.example.sso.admin.internal.application.UserDetailView;
import com.example.sso.admin.internal.application.UserDevicesView;
import com.example.sso.admin.internal.application.UserSessionView;

import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.portal.AppType;
import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.AppPolicyRequest;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.saml.RelyingPartyRequest;
import com.example.sso.saml.RelyingPartyView;
import com.example.sso.saml.SamlRelyingPartyAdminService;
import com.example.sso.scim.IssueScimTokenRequest;
import com.example.sso.scim.ScimTokenIssued;
import com.example.sso.session.IpRuleRequest;
import com.example.sso.session.IpRuleService;
import com.example.sso.session.IpRuleView;
import com.example.sso.session.SessionPolicyRequest;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicySpec;
import com.example.sso.session.SessionPolicyUpdate;
import com.example.sso.session.SessionPolicyView;
import com.example.sso.user.GroupMembersPage;
import com.example.sso.user.GroupRequest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.GroupView;
import com.example.sso.user.Permissions;
import com.example.sso.user.Suggestion;
import com.example.sso.user.UserGroupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Admin REST API. URL access requires ROLE_ADMIN (RBAC, see SecurityConfig); each method
 * additionally enforces a fine-grained permission (PBAC) via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserAdminService userAdminService;
    private final UserDetailAdminService userDetailAdminService;
    private final AdminAuditLogger auditLogger;
    private final ClientAdminService clientAdminService;
    private final SamlRelyingPartyAdminService samlRelyingParties;
    private final SessionPolicyService sessionPolicy;
    private final IpRuleService ipRules;
    private final ApplicationService applications;
    private final UserGroupService userGroups;
    private final AdminService adminService;
    private final AdminPortalSettingsService adminPortalSettings;

    // --- Users ---

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public List<AdminUserView> users() {
        return userAdminService.listUsers();
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "') and @adminAccessPolicy.canAccessUser(#id)")
    public UserDetailView userDetail(@PathVariable UUID id) {
        return userAdminService.getUser(id);
    }

    @GetMapping("/users/{id}/applications")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "') and @adminAccessPolicy.canAccessUser(#id)")
    public List<ApplicationView> userApplications(@PathVariable UUID id) {
        return userDetailAdminService.applications(id);
    }

    @GetMapping("/users/{id}/devices")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "') and @adminAccessPolicy.canAccessUser(#id)")
    public UserDevicesView userDevices(@PathVariable UUID id) {
        return userDetailAdminService.devices(id);
    }

    @GetMapping("/users/{id}/sessions")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "') and @adminAccessPolicy.canAccessUser(#id)")
    public List<UserSessionView> userSessions(@PathVariable UUID id) {
        return userDetailAdminService.sessions(id);
    }

    @GetMapping("/users/{id}/activity")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "') and @adminAccessPolicy.canAccessUser(#id)")
    public List<AuditEntry> userActivity(@PathVariable UUID id) {
        return userDetailAdminService.activity(id);
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "') and @adminAccessPolicy.canCreateUser()")
    public ResponseEntity<AdminUserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdminService.createUser(request));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
            + "') and @adminAccessPolicy.canAccessUser(#id)"
            + " and @adminAccessPolicy.canUpdateUser(#id, #request.enabled(), #request.roles())")
    public AdminUserView updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.updateUser(id, request);
    }

    @PostMapping("/users/{id}/enabled")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
            + "') and @adminAccessPolicy.canAccessUser(#id)"
            + " and @adminAccessPolicy.canSetEnabled(#id, #body.enabled())")
    public AdminUserView setEnabled(@PathVariable UUID id, @Valid @RequestBody SetEnabledRequest body) {
        return userAdminService.setEnabled(id, body.enabled());
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_DELETE
            + "') and @adminAccessPolicy.canAccessUser(#id) and @adminAccessPolicy.canDeleteUser(#id)")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userAdminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reset-mfa")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
            + "') and @adminAccessPolicy.canAccessUser(#id) and @adminAccessPolicy.canResetMfa(#id)")
    public ResponseEntity<Void> resetMfa(@PathVariable UUID id) {
        userAdminService.resetUserMfa(id);
        return ResponseEntity.noContent().build();
    }

    // --- Roles ---

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public List<RoleView> roles() {
        return userAdminService.listRoles();
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_CREATE + "')")
    public ResponseEntity<RoleView> createRole(@Valid @RequestBody RoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userAdminService.createRole(request.name(), request.permissions()));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_UPDATE + "')")
    public RoleView updateRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        return userAdminService.updateRole(id, request.name(), request.permissions());
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_DELETE + "')")
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        userAdminService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public List<PermissionView> permissions() {
        return userAdminService.listPermissions();
    }

    @PutMapping("/users/{id}/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE
            + "') and @adminAccessPolicy.canAccessUser(#id) and @adminAccessPolicy.canManagePermissions(#id)")
    public AdminUserView setUserPermissions(@PathVariable UUID id, @Valid @RequestBody SetPermissionsRequest body) {
        return userAdminService.setUserPermissions(id, body.permissions());
    }

    // --- Groups (organizational directory; sync-ready via external id) ---

    @GetMapping("/groups")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_READ + "')")
    public List<GroupView> groups() {
        return userGroups.listAll();
    }

    @PostMapping("/groups")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_CREATE + "')")
    public ResponseEntity<GroupView> createGroup(@Valid @RequestBody GroupRequest request) {
        GroupView created = userGroups.create(new GroupSpec(request.name(), request.description(),
                request.externalId(), groupIds(request.memberUserIds())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE + "')")
    public GroupView updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest request) {
        return userGroups.update(id, new GroupSpec(request.name(), request.description(),
                request.externalId(), groupIds(request.memberUserIds())));
    }

    @DeleteMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_DELETE + "')")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        userGroups.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Replaces the roles delegated to a group; members inherit them. */
    @PutMapping("/groups/{id}/roles")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE + "')")
    public GroupView setGroupRoles(@PathVariable UUID id, @RequestBody SetGroupRolesRequest request) {
        Set<String> roleNames = request.roleNames() == null ? Set.of() : request.roleNames();
        GroupView view = userGroups.setRoles(id, roleNames);
        auditLogger.log("GROUP_ROLES_UPDATED", "group=" + id + " roles=" + roleNames);
        return view;
    }

    /** Replaces the group's managers (scoped admins allowed to manage its members). */
    @PutMapping("/groups/{id}/managers")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_UPDATE + "')")
    public GroupView setGroupManagers(@PathVariable UUID id, @RequestBody SetGroupManagersRequest request) {
        GroupView view = userGroups.setManagers(id, groupIds(request.managerUserIds()));
        auditLogger.log("GROUP_MANAGERS_UPDATED", "group=" + id + " managers=" + request.managerUserIds());
        return view;
    }

    // --- Group detail page (members paginated + assigned apps) ---
    @GetMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_READ + "')")
    public GroupView group(@PathVariable UUID id) {
        return userGroups.get(id);
    }

    @GetMapping("/groups/{id}/members")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_READ + "')")
    public GroupMembersPage groupMembers(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return userGroups.members(id, page, size);
    }

    @GetMapping("/groups/{id}/applications")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_READ + "')")
    public List<ApplicationView> groupApplications(@PathVariable UUID id) {
        return applications.appsForGroup(id);
    }

    // --- Typeahead search for the app-assignment pickers ---
    @GetMapping("/groups/search")
    @PreAuthorize("hasAuthority('" + Permissions.GROUP_READ + "')")
    public List<Suggestion> searchGroups(@RequestParam(name = "q", defaultValue = "") String q,
                                         @RequestParam(defaultValue = "20") int limit) {
        return userGroups.search(q, limit);
    }

    @GetMapping("/users/search")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public List<Suggestion> searchUsers(@RequestParam(name = "q", defaultValue = "") String q,
                                        @RequestParam(defaultValue = "20") int limit) {
        return userAdminService.searchUsers(q, limit);
    }

    private static Set<UUID> groupIds(List<String> values) {
        return values == null ? Set.of()
                : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    // --- Clients / SAML / audit / tokens / keys ---

    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('" + Permissions.CLIENT_READ + "')")
    public List<ClientView> clients() {
        return clientAdminService.listClients();
    }

    @PostMapping("/clients")
    @PreAuthorize("hasAuthority('" + Permissions.CLIENT_CREATE + "')")
    public ResponseEntity<ClientCreated> createClient(@Valid @RequestBody CreateClientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientAdminService.createClient(request));
    }

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CLIENT_DELETE + "')")
    public ResponseEntity<Void> deleteClient(@PathVariable String id) {
        clientAdminService.deleteClient(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/saml/relying-parties")
    @PreAuthorize("hasAuthority('" + Permissions.SAML_READ + "')")
    public List<RelyingPartyView> relyingParties() {
        return samlRelyingParties.list();
    }

    @PostMapping("/saml/relying-parties")
    @PreAuthorize("hasAuthority('" + Permissions.SAML_CREATE + "')")
    public ResponseEntity<RelyingPartyView> createRelyingParty(@Valid @RequestBody RelyingPartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(samlRelyingParties.create(request));
    }

    @PutMapping("/saml/relying-parties/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SAML_UPDATE + "')")
    public RelyingPartyView updateRelyingParty(@PathVariable UUID id, @Valid @RequestBody RelyingPartyRequest request) {
        return samlRelyingParties.update(id, request);
    }

    @DeleteMapping("/saml/relying-parties/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SAML_DELETE + "')")
    public ResponseEntity<Void> deleteRelyingParty(@PathVariable UUID id) {
        samlRelyingParties.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_READ + "')")
    public List<AuditEntry> audit(@RequestParam(name = "category", required = false) AuditCategory category) {
        return adminService.recentAudit(category);
    }

    @PostMapping("/scim/tokens")
    @PreAuthorize("hasAuthority('" + Permissions.SCIM_MANAGE + "')")
    public ScimTokenIssued issueScimToken(@Valid @RequestBody IssueScimTokenRequest request) {
        return adminService.issueScimToken(request);
    }

    @PostMapping("/keys/rotate")
    @PreAuthorize("hasAuthority('" + Permissions.KEY_ROTATE + "')")
    public Map<String, String> rotateSigningKey() {
        return Map.of("activeKid", adminService.rotateSigningKey());
    }

    @PostMapping("/saml/keys/rotate")
    @PreAuthorize("hasAuthority('" + Permissions.KEY_ROTATE + "')")
    public Map<String, String> rotateSamlSigningKey() {
        return Map.of("keyId", adminService.rotateSamlSigningKey());
    }

    // --- Session policy + network (IP) access ---

    @GetMapping("/session-policies")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_POLICY_READ + "')")
    public List<SessionPolicyView> sessionPolicies() {
        return sessionPolicy.listAll().stream().map(SessionPolicyView::of).toList();
    }

    @PostMapping("/session-policies")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_POLICY_CREATE + "')")
    public ResponseEntity<SessionPolicyView> createSessionPolicy(@Valid @RequestBody SessionPolicyRequest request) {
        SessionPolicyView created = SessionPolicyView.of(sessionPolicy.create(new SessionPolicySpec(request.name(),
                request.priority(), request.enabled(), request.absoluteTimeoutMinutes(), request.idleTimeoutMinutes(),
                request.reauthIntervalMinutes(), request.reauthFactors(), request.bindClient(),
                request.maxConcurrentSessions(), request.rotateOnReauth(), request.cookieSameSite(),
                policyIds(request.assignedUserIds()), policyIds(request.assignedRoleIds()))));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/session-policies/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_POLICY_UPDATE + "')")
    public SessionPolicyView updateSessionPolicy(@PathVariable UUID id,
                                                 @Valid @RequestBody SessionPolicyRequest request) {
        return SessionPolicyView.of(sessionPolicy.update(id, new SessionPolicyUpdate(request.priority(),
                request.enabled(), request.absoluteTimeoutMinutes(), request.idleTimeoutMinutes(),
                request.reauthIntervalMinutes(), request.reauthFactors(), request.bindClient(),
                request.maxConcurrentSessions(), request.rotateOnReauth(), request.cookieSameSite(),
                policyIds(request.assignedUserIds()), policyIds(request.assignedRoleIds()))));
    }

    @DeleteMapping("/session-policies/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_POLICY_DELETE + "')")
    public ResponseEntity<Void> deleteSessionPolicy(@PathVariable UUID id) {
        sessionPolicy.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static Set<UUID> policyIds(List<String> values) {
        return values == null ? Set.of()
                : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    // --- Admin portal security (elevation freshness + admin session lifetimes) ---
    @GetMapping("/portal-settings")
    @PreAuthorize("hasAuthority('" + Permissions.PORTAL_SETTINGS_READ + "')")
    public AdminPortalSettingsView portalSettings() {
        return AdminPortalSettingsView.of(adminPortalSettings.get());
    }

    @PutMapping("/portal-settings")
    @PreAuthorize("hasAuthority('" + Permissions.PORTAL_SETTINGS_UPDATE + "')")
    public AdminPortalSettingsView updatePortalSettings(@Valid @RequestBody AdminPortalSettingsRequest request) {
        return AdminPortalSettingsView.of(adminPortalSettings.update(request));
    }

    @GetMapping("/ip-rules")
    @PreAuthorize("hasAuthority('" + Permissions.IP_RULE_READ + "')")
    public List<IpRuleView> ipRules() {
        return ipRules.list();
    }

    @PostMapping("/ip-rules")
    @PreAuthorize("hasAuthority('" + Permissions.IP_RULE_CREATE + "')")
    public ResponseEntity<IpRuleView> createIpRule(@Valid @RequestBody IpRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ipRules.create(request));
    }

    @PutMapping("/ip-rules/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.IP_RULE_UPDATE + "')")
    public IpRuleView updateIpRule(@PathVariable UUID id, @Valid @RequestBody IpRuleRequest request) {
        return ipRules.update(id, request);
    }

    @DeleteMapping("/ip-rules/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.IP_RULE_DELETE + "')")
    public ResponseEntity<Void> deleteIpRule(@PathVariable UUID id) {
        ipRules.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Application assignment (user portal) ---

    @GetMapping("/applications")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGNMENT_READ + "')")
    public List<ApplicationView> applications() {
        return applications.listApplications();
    }

    @GetMapping("/applications/{type}/{id}/assignments")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGNMENT_READ + "')")
    public List<AppAssignmentView> appAssignments(@PathVariable AppType type, @PathVariable String id) {
        return applications.assignmentsForApp(type, id);
    }

    @PostMapping("/applications/assignments")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGNMENT_ASSIGN + "')")
    public ResponseEntity<AppAssignmentView> assignApp(@Valid @RequestBody AssignAppRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applications.assign(request));
    }

    @DeleteMapping("/applications/assignments/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGNMENT_UNASSIGN + "')")
    public ResponseEntity<Void> unassignApp(@PathVariable UUID id) {
        applications.unassign(id);
        return ResponseEntity.noContent().build();
    }

    /** Sets (or clears, when requiredPolicyId is blank) the app-level sign-on policy for an application. */
    @PutMapping("/applications/{type}/{id}/policy")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGNMENT_ASSIGN + "')")
    public ResponseEntity<Void> setAppPolicy(@PathVariable AppType type, @PathVariable String id,
                                             @RequestBody AppPolicyRequest request) {
        applications.setAppPolicy(type, id, request.requiredPolicyId());
        return ResponseEntity.noContent().build();
    }
}
