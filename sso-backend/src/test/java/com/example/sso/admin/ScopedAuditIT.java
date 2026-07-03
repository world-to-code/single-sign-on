package com.example.sso.admin;

import com.example.sso.admin.internal.shared.application.AdminService;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.NewUser;
import com.example.sso.user.Roles;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 scoped audit log: a delegated admin sees audit entries only for subjects inside their subtree
 * (plus their own actions); a super admin sees everything. Records events directly with structured
 * subjects, then queries {@link AdminService#recentAudit} under different actors.
 */
class ScopedAuditIT extends AbstractIntegrationTest {

    @Autowired
    AdminService adminService;
    @Autowired
    AuditService audit;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    private UUID delegate;
    private UUID inScopeGroup;
    private UUID outOfScopeGroup;

    @BeforeEach
    void setUp() {
        ResourceType any = types.save(new ResourceType("AUDIT-ANY",
                Set.of(MemberType.GROUP, MemberType.USER)));
        delegate = user("audit-delegate");
        inScopeGroup = group("Audit-InScope");
        outOfScopeGroup = group("Audit-OutOfScope");

        Resource team = new Resource("Audit-Team", any);
        team.grant(ResourceGrant.admin(delegate));
        team.attachMember(ResourceMember.group(inScopeGroup));
        resources.save(team);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdGroups.forEach(userGroups::delete);
        createdGroups.clear();
        createdUsers.forEach(userService::delete);
        createdUsers.clear();
    }

    @Test
    void aDelegateSeesInScopeSubjectsButNotOutOfScopeOnes() {
        record(AuditType.GROUP_ROLES_UPDATED, "admin", AuditSubjectType.GROUP, inScopeGroup.toString());
        record(AuditType.GROUP_ROLES_UPDATED, "admin", AuditSubjectType.GROUP, outOfScopeGroup.toString());

        asDelegate(delegate);
        List<String> subjects = adminService.recentAudit(null, 0, 100).items().stream()
                .filter(e -> e.subjectType() == AuditSubjectType.GROUP)
                .map(AuditEntry::subjectId).toList();

        assertThat(subjects).contains(inScopeGroup.toString());
        assertThat(subjects).doesNotContain(outOfScopeGroup.toString());
    }

    @Test
    void aDelegateSeesTheirOwnActionsEvenWithoutAScopeableSubject() {
        record(AuditType.AUTH_SUCCESS, "audit-delegate", AuditSubjectType.NONE, null);
        record(AuditType.AUTH_SUCCESS, "someone-else", AuditSubjectType.NONE, null);

        asDelegate(delegate);
        List<String> principals = adminService.recentAudit(null, 0, 100).items().stream()
                .filter(e -> e.type().equals(AuditType.AUTH_SUCCESS.name()))
                .map(AuditEntry::principal).toList();

        assertThat(principals).contains("audit-delegate");
        assertThat(principals).doesNotContain("someone-else");
    }

    @Test
    void aSuperAdminSeesEveryEntry() {
        record(AuditType.GROUP_ROLES_UPDATED, "admin", AuditSubjectType.GROUP, outOfScopeGroup.toString());
        record(AuditType.AUTH_SUCCESS, "someone-else", AuditSubjectType.NONE, null);

        asRole(Roles.ADMIN, "admin");
        List<AuditEntry> all = adminService.recentAudit(null, 0, 100).items();

        assertThat(all).anyMatch(e -> outOfScopeGroup.toString().equals(e.subjectId()));
        assertThat(all).anyMatch(e -> "someone-else".equals(e.principal()));
    }

    private void record(AuditType type, String principal, AuditSubjectType subjectType, String subjectId) {
        audit.record(new AuditRecord(type, principal, true, "detail", null, subjectType, subjectId));
    }

    private void asDelegate(UUID userId) {
        String username = userService.findById(userId).orElseThrow().getUsername();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("audit:read"))));
    }

    private void asRole(String role, String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority(role))));
    }

    private UUID user(String username) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID group(String name) {
        UUID id = UUID.fromString(userGroups.create(new GroupSpec(name, null, null, Set.of())).id());
        createdGroups.add(id);
        return id;
    }
}
