package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.audit.application.AuditAccessPolicy;
import com.example.sso.audit.AuditCategory;
import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditType;
import com.example.sso.session.lifecycle.SessionMetadata;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.lifecycle.UserSessions;
import java.time.Instant;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.group.GroupMembership;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the console reports a user as HOLDING, and where each of it came from.
 *
 * <p>Untested until now, on both halves. {@code roleAssignments} merges the roles assigned to the account with
 * the ones its groups delegate and stamps each with its source, and {@code effectivePermissions} unions three
 * sources and expands read-implication over them. Neither is a lookup — they are the answer an administrator
 * reads before deciding whether someone is over-privileged, so getting the SOURCE wrong is worse than showing
 * nothing: a role inherited from a group, reported as directly assigned, is one an admin would try to revoke
 * on the user and find still there.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailAdminServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private static final String USERNAME = "ada";
    private static final UUID ORG = UUID.randomUUID();

    @Mock private UserService userService;
    @Mock private UserGroupService userGroups;
    @Mock private SessionMetadataStore sessionMetadata;
    @Mock private UserSessions userSessions;
    @Mock private AuditService audit;
    @Mock private AuditAccessPolicy auditAccessPolicy;
    @InjectMocks private UserDetailAdminService service;

    private RoleRef role(String name, String... permissions) {
        RoleRef ref = mock(RoleRef.class);
        lenient().when(ref.getId()).thenReturn(UUID.randomUUID());
        lenient().when(ref.getName()).thenReturn(name);
        lenient().when(ref.getPermissionNames()).thenReturn(Set.of(permissions));
        return ref;
    }

    private UserAccount account(Set<RoleRef> roles, Set<String> directPermissions) {
        UserAccount user = mock(UserAccount.class);
        lenient().when(user.getId()).thenReturn(USER);
        lenient().doReturn(roles).when(user).getRoles();
        lenient().when(user.getDirectPermissionNames()).thenReturn(directPermissions);
        return user;
    }

    private UserDetailView detailOf(UserAccount user, List<GroupMembership> memberships) {
        when(userService.findById(USER)).thenReturn(Optional.of(user));
        when(userGroups.membershipsForUser(USER)).thenReturn(memberships);
        return service.getUser(USER);
    }

    @Test
    void anUnknownUserIsNotFound() {
        when(userService.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(USER)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRoleAssignedToTheAccountIsReportedAsDirect() {
        UserDetailView detail = detailOf(account(Set.of(role("ROLE_USER")), Set.of()), List.of());

        assertThat(detail.roleAssignments()).singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.roleName()).isEqualTo("ROLE_USER");
                    assertThat(assignment.direct()).isTrue();
                    assertThat(assignment.viaGroups()).isEmpty();
                });
    }

    /**
     * The distinction that matters: a group-delegated role is NOT direct, and names the group it came from.
     * Reported as direct, an administrator revokes it on the user and it is still there.
     */
    @Test
    void aRoleDelegatedByAGroupIsReportedAgainstThatGroupRatherThanAsDirect() {
        GroupMembership platform = new GroupMembership(UUID.randomUUID(), "platform",
                List.of(role("ROLE_GROUP_ADMIN")));

        UserDetailView detail = detailOf(account(Set.of(), Set.of()), List.of(platform));

        assertThat(detail.roleAssignments()).singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.roleName()).isEqualTo("ROLE_GROUP_ADMIN");
                    assertThat(assignment.direct()).isFalse();
                    assertThat(assignment.viaGroups()).containsExactly("platform");
                });
    }

    /** Held both ways, it is ONE row carrying both sources — not two rows, and not either one alone. */
    @Test
    void aRoleHeldDirectlyAndThroughAGroupCarriesBothSources() {
        RoleRef shared = role("ROLE_USER");
        GroupMembership staff = new GroupMembership(UUID.randomUUID(), "staff", List.of(shared));

        UserDetailView detail = detailOf(account(Set.of(shared), Set.of()), List.of(staff));

        assertThat(detail.roleAssignments()).singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.direct()).isTrue();
                    assertThat(assignment.viaGroups()).containsExactly("staff");
                });
    }

    /** Two groups delegating the same role list both, so an admin knows every place to remove it from. */
    @Test
    void everyGroupThatDelegatesARoleIsNamed() {
        RoleRef shared = role("ROLE_USER");
        List<GroupMembership> memberships = List.of(
                new GroupMembership(UUID.randomUUID(), "platform", List.of(shared)),
                new GroupMembership(UUID.randomUUID(), "compilers", List.of(shared)));

        UserDetailView detail = detailOf(account(Set.of(), Set.of()), memberships);

        assertThat(detail.roleAssignments()).singleElement()
                .extracting(RoleAssignmentView::viaGroups)
                .satisfies(groups -> assertThat(groups).containsExactlyInAnyOrder("platform", "compilers"));
    }

    @Test
    void assignmentsAreOrderedByNameSoTheListIsStable() {
        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_USER"), role("ROLE_ADMIN"), role("ROLE_group_admin")), Set.of()),
                List.of());

        assertThat(detail.roleAssignments()).extracting(RoleAssignmentView::roleName)
                .containsExactly("ROLE_ADMIN", "ROLE_group_admin", "ROLE_USER");
    }

    /** Effective permissions union all three sources: the account's roles, its groups' roles, and its own. */
    @Test
    void effectivePermissionsUnionRolesGroupRolesAndDirectGrants() {
        GroupMembership platform = new GroupMembership(UUID.randomUUID(), "platform",
                List.of(role("ROLE_GROUP_ADMIN", Permissions.GROUP_UPDATE)));

        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_USER", Permissions.USER_READ)), Set.of(Permissions.CLIENT_READ)),
                List.of(platform));

        // Exactly, not contains: this list answers "is this person over-privileged", so the failure direction
        // that matters is it being too LARGE — a subset assertion is blind to exactly that.
        assertThat(detail.effectivePermissions()).containsExactlyInAnyOrder(
                Permissions.USER_READ, Permissions.GROUP_UPDATE, Permissions.GROUP_READ, Permissions.CLIENT_READ);
    }

    /**
     * Read-implication is applied to the roll-up, not just to the login authorities: a mutating permission
     * grants the matching read, so the console must not report someone as unable to list what they may edit.
     */
    @Test
    void aMutatingPermissionAlsoReportsTheReadItImplies() {
        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_GROUP_ADMIN", Permissions.GROUP_UPDATE)), Set.of()), List.of());

        assertThat(detail.effectivePermissions())
                .containsExactlyInAnyOrder(Permissions.GROUP_UPDATE, Permissions.GROUP_READ);
    }

    // --- the two filters that are the point of these methods -------------------------------------

    private UserAccount targetUser() {
        UserAccount user = account(Set.of(), Set.of());
        lenient().when(user.getUsername()).thenReturn(USERNAME);
        lenient().when(user.getOrgId()).thenReturn(ORG);
        when(userService.findById(USER)).thenReturn(Optional.of(user));
        return user;
    }

    private SessionMetadata session(String sessionId, String ip) {
        return new SessionMetadata("handle-" + sessionId, sessionId, USERNAME, "Firefox", ip, Instant.EPOCH);
    }

    private AuditEntry entryWithPii() {
        return new AuditEntry(1L, Instant.EPOCH, USERNAME, "USER_UPDATED", AuditCategory.ADMIN, true, null,
                null, null, null, UUID.randomUUID(), "ada@example.com", "Ada L", "203.0.113.9", "Firefox",
                "laptop", "req-1", null, AuditSeverity.INFO);
    }

    /**
     * A username is unique only WITHIN an org, so the metadata store — which is keyed on username alone — can
     * hold a same-named user's sessions from another tenant. Their IP, device and activity are PII, and this
     * filter is the only thing between them and this tenant's console.
     */
    @Test
    void aSameNamedUsersSessionInAnotherTenantIsNotShown() {
        targetUser();
        when(userSessions.sessionIdsForUser(USERNAME, ORG)).thenReturn(Set.of("mine"));
        when(sessionMetadata.forUser(USERNAME))
                .thenReturn(List.of(session("mine", "10.0.0.1"), session("theirs", "203.0.113.9")));

        assertThat(service.sessions(USER)).extracting(UserSessionView::ip).containsExactly("10.0.0.1");
    }

    /**
     * A viewer holding {@code user:read} but not {@code audit:read:pii} must not read the actor's email,
     * account id or client context off this tab — the same gate the main audit console applies.
     */
    @Test
    void activityIsRedactedForAViewerWithoutThePiiGrant() {
        targetUser();
        when(audit.recentForPrincipal(ORG, USERNAME)).thenReturn(List.of(entryWithPii()));
        when(auditAccessPolicy.canReadPii()).thenReturn(false);

        AuditEntry shown = service.activity(USER, 0, 20).items().getFirst();

        assertThat(shown.actorEmail()).isNull();
        assertThat(shown.actorId()).isNull();
        assertThat(shown.remoteIp()).isNull();
        assertThat(shown.userAgent()).isNull();
        assertThat(shown.device()).isNull();
        // The principal name is the event's own subject, not actor PII — it stays.
        assertThat(shown.principal()).isEqualTo(USERNAME);
    }

    @Test
    void activityKeepsThePiiForAViewerHoldingTheGrant() {
        targetUser();
        when(audit.recentForPrincipal(ORG, USERNAME)).thenReturn(List.of(entryWithPii()));
        when(auditAccessPolicy.canReadPii()).thenReturn(true);

        AuditEntry shown = service.activity(USER, 0, 20).items().getFirst();

        assertThat(shown.actorEmail()).isEqualTo("ada@example.com");
        assertThat(shown.remoteIp()).isEqualTo("203.0.113.9");
    }

    /** Activity is read for the target's OWN org, or a same-named principal elsewhere bleeds into the list. */
    @Test
    void activityIsReadForTheTargetsOwnOrganization() {
        targetUser();
        when(audit.recentForPrincipal(ORG, USERNAME)).thenReturn(List.of());
        when(auditAccessPolicy.canReadPii()).thenReturn(true);

        service.activity(USER, 0, 20);

        verify(audit).recentForPrincipal(ORG, USERNAME);
    }

    /** Force-expiry is org-scoped too, and leaves a trail — it is a privilege action on someone else. */
    @Test
    void terminatingSessionsIsScopedToTheOrgAndAudited() {
        targetUser();
        when(userSessions.terminateForUser(USERNAME, ORG)).thenReturn(3);

        assertThat(service.terminateSessions(USER)).isEqualTo(3);

        verify(userSessions).terminateForUser(USERNAME, ORG);
        verify(audit).record(argThat(record -> record.type() == AuditType.SESSION_ADMIN_REVOKED
                && record.detail().contains("3")));
    }

    /** Direct permissions are reported separately from the roll-up, so the two are not conflated. */
    @Test
    void directPermissionsAreReportedOnTheirOwn() {
        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_USER", Permissions.USER_READ)), Set.of(Permissions.CLIENT_READ)),
                List.of());

        assertThat(detail.directPermissions()).containsExactly(Permissions.CLIENT_READ);
    }
}
