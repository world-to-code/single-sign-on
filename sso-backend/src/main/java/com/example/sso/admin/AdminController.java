package com.example.sso.admin;

import com.example.sso.saml.RelyingPartyRequest;
import com.example.sso.saml.RelyingPartyView;
import com.example.sso.saml.SamlRelyingPartyAdminService;
import com.example.sso.scim.IssueScimTokenRequest;
import com.example.sso.scim.ScimTokenIssued;
import com.example.sso.portal.AppAssignment;
import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.session.IpRuleRequest;
import com.example.sso.session.IpRuleService;
import com.example.sso.session.IpRuleView;
import com.example.sso.session.SessionPolicyRequest;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.session.SessionPolicyView;
import com.example.sso.user.GroupRequest;
import com.example.sso.user.GroupView;
import com.example.sso.user.Permissions;
import com.example.sso.user.UserGroup;
import com.example.sso.user.UserGroupService;

import jakarta.validation.Valid;

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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin REST API. URL access requires ROLE_ADMIN (RBAC, see SecurityConfig); each method
 * additionally enforces a fine-grained permission (PBAC) via {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserAdminService userAdminService;
    private final ClientAdminService clientAdminService;
    private final SamlRelyingPartyAdminService samlRelyingParties;
    private final SessionPolicyService sessionPolicy;
    private final IpRuleService ipRules;
    private final ApplicationService applications;
    private final UserGroupService userGroups;
    private final AdminService adminService;

    public AdminController(UserAdminService userAdminService, ClientAdminService clientAdminService,
                          SamlRelyingPartyAdminService samlRelyingParties, SessionPolicyService sessionPolicy,
                          IpRuleService ipRules, ApplicationService applications, UserGroupService userGroups,
                          AdminService adminService) {
        this.userAdminService = userAdminService;
        this.clientAdminService = clientAdminService;
        this.samlRelyingParties = samlRelyingParties;
        this.sessionPolicy = sessionPolicy;
        this.ipRules = ipRules;
        this.applications = applications;
        this.userGroups = userGroups;
        this.adminService = adminService;
    }

    // --- Users ---

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public List<AdminUserView> users() {
        return userAdminService.listUsers();
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public ResponseEntity<AdminUserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdminService.createUser(request));
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public AdminUserView updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.updateUser(id, request);
    }

    @PostMapping("/users/{id}/enabled")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public AdminUserView setEnabled(@PathVariable UUID id, @Valid @RequestBody SetEnabledRequest body) {
        return userAdminService.setEnabled(id, body.enabled());
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userAdminService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reset-mfa")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
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

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public List<String> permissions() {
        return userAdminService.listPermissions();
    }

    @PutMapping("/users/{id}/permissions")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public AdminUserView setUserPermissions(@PathVariable UUID id, @Valid @RequestBody SetPermissionsRequest body) {
        return userAdminService.setUserPermissions(id, body.permissions());
    }

    // --- Groups (organizational directory; sync-ready via external id) ---

    @GetMapping("/groups")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public List<GroupView> groups() {
        return userGroups.listAll().stream().map(AdminController::toView).toList();
    }

    @PostMapping("/groups")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public ResponseEntity<GroupView> createGroup(@Valid @RequestBody GroupRequest request) {
        GroupView created = toView(userGroups.create(request.name(), request.description(),
                request.externalId(), groupIds(request.memberUserIds())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public GroupView updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest request) {
        return toView(userGroups.update(id, request.name(), request.description(),
                request.externalId(), groupIds(request.memberUserIds())));
    }

    @DeleteMapping("/groups/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        userGroups.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static Set<UUID> groupIds(List<String> values) {
        return values == null ? Set.of()
                : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    private static GroupView toView(UserGroup group) {
        List<String> memberIds = group.getMemberUserIds().stream().map(UUID::toString).toList();
        return new GroupView(group.getId().toString(), group.getName(), group.getDescription(),
                group.getExternalId(), memberIds, memberIds.size());
    }

    // --- Clients / SAML / audit / tokens / keys ---

    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('" + Permissions.CLIENT_READ + "')")
    public List<ClientView> clients() {
        return clientAdminService.listClients();
    }

    @PostMapping("/clients")
    @PreAuthorize("hasAuthority('" + Permissions.CLIENT_WRITE + "')")
    public ResponseEntity<ClientCreated> createClient(@Valid @RequestBody CreateClientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientAdminService.createClient(request));
    }

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.CLIENT_WRITE + "')")
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
    @PreAuthorize("hasAuthority('" + Permissions.SAML_WRITE + "')")
    public ResponseEntity<RelyingPartyView> createRelyingParty(@Valid @RequestBody RelyingPartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(samlRelyingParties.create(request));
    }

    @PutMapping("/saml/relying-parties/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SAML_WRITE + "')")
    public RelyingPartyView updateRelyingParty(@PathVariable UUID id, @Valid @RequestBody RelyingPartyRequest request) {
        return samlRelyingParties.update(id, request);
    }

    @DeleteMapping("/saml/relying-parties/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SAML_WRITE + "')")
    public ResponseEntity<Void> deleteRelyingParty(@PathVariable UUID id) {
        samlRelyingParties.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_READ + "')")
    public List<AuditView> audit() {
        return adminService.recentAudit();
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
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public List<SessionPolicyView> sessionPolicies() {
        return sessionPolicy.listAll().stream().map(SessionPolicyView::of).toList();
    }

    @PostMapping("/session-policies")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public ResponseEntity<SessionPolicyView> createSessionPolicy(@Valid @RequestBody SessionPolicyRequest request) {
        SessionPolicyView created = SessionPolicyView.of(sessionPolicy.create(request.name(), request.priority(),
                request.enabled(), request.absoluteTimeoutMinutes(), request.idleTimeoutMinutes(),
                request.reauthIntervalMinutes(), request.reauthFactors(), request.bindClient(),
                request.maxConcurrentSessions(), request.rotateOnReauth(), request.cookieSameSite(),
                policyIds(request.assignedUserIds()), policyIds(request.assignedRoleIds())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/session-policies/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public SessionPolicyView updateSessionPolicy(@PathVariable UUID id,
                                                 @Valid @RequestBody SessionPolicyRequest request) {
        return SessionPolicyView.of(sessionPolicy.update(id, request.priority(), request.enabled(),
                request.absoluteTimeoutMinutes(), request.idleTimeoutMinutes(), request.reauthIntervalMinutes(),
                request.reauthFactors(), request.bindClient(), request.maxConcurrentSessions(),
                request.rotateOnReauth(), request.cookieSameSite(),
                policyIds(request.assignedUserIds()), policyIds(request.assignedRoleIds())));
    }

    @DeleteMapping("/session-policies/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public ResponseEntity<Void> deleteSessionPolicy(@PathVariable UUID id) {
        sessionPolicy.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static Set<UUID> policyIds(List<String> values) {
        return values == null ? Set.of()
                : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    @GetMapping("/ip-rules")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public List<IpRuleView> ipRules() {
        return ipRules.list();
    }

    @PostMapping("/ip-rules")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public ResponseEntity<IpRuleView> createIpRule(@Valid @RequestBody IpRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ipRules.create(request));
    }

    @PutMapping("/ip-rules/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public IpRuleView updateIpRule(@PathVariable UUID id, @Valid @RequestBody IpRuleRequest request) {
        return ipRules.update(id, request);
    }

    @DeleteMapping("/ip-rules/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.SESSION_MANAGE + "')")
    public ResponseEntity<Void> deleteIpRule(@PathVariable UUID id) {
        ipRules.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Application assignment (user portal) ---

    @GetMapping("/applications")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGN + "')")
    public List<ApplicationView> applications() {
        return applications.listApplications();
    }

    @GetMapping("/applications/{type}/{id}/assignments")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGN + "')")
    public List<AppAssignmentView> appAssignments(@PathVariable AppAssignment.AppType type, @PathVariable String id) {
        return applications.assignmentsForApp(type, id);
    }

    @PostMapping("/applications/assignments")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGN + "')")
    public ResponseEntity<AppAssignmentView> assignApp(@Valid @RequestBody AssignAppRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applications.assign(request));
    }

    @DeleteMapping("/applications/assignments/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.APP_ASSIGN + "')")
    public ResponseEntity<Void> unassignApp(@PathVariable UUID id) {
        applications.unassign(id);
        return ResponseEntity.noContent().build();
    }
}
