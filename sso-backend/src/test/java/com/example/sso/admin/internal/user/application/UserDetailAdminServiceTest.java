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
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.group.GroupMembership;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.rbac.DecisionReason;
import com.example.sso.user.rbac.PermissionExplanation;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the console reports a user as HOLDING, and where each of it came from.
 *
 * <p>{@code roleAssignments} merges the roles assigned to the account with the ones its groups delegate and
 * stamps each with its source; {@code effectivePermissions} is the deny-applied resolved authorities (role
 * NAMES filtered out) and {@code deniedPermissions} is what a grant hands out but a deny removed. Neither is a
 * lookup — they are the answer an administrator reads before deciding whether someone is over-privileged, so
 * getting the SOURCE wrong is worse than showing nothing: a role inherited from a group, reported as directly
 * assigned, is one an admin would try to revoke on the user and find still there.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailAdminServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private static final String USERNAME = "ada";
    private static final UUID ORG = UUID.randomUUID();

    @Mock private UserService userService;
    @Mock private DenyService denyService;
    @Mock private UserGroupService userGroups;
    @Mock private SessionMetadataStore sessionMetadata;
    @Mock private UserSessions userSessions;
    @Mock private AuditService audit;
    @Mock private AuditAccessPolicy auditAccessPolicy;
    @InjectMocks private UserDetailAdminService service;

    @BeforeEach
    void defaults() {
        // Defaults so getUser never NPEs; the permission tests override the resolved authorities per-user.
        lenient().when(userService.effectiveAuthorities(any())).thenReturn(Set.of());
        lenient().when(denyService.userDenies(any())).thenReturn(List.of());
    }

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

    /** Effective permissions are the deny-applied RESOLVED authorities, with role NAMES filtered out (only
     *  {@code resource:action}-shaped authorities are permissions). */
    @Test
    void effectivePermissionsAreTheResolvedAuthoritiesMinusRoleNames() {
        when(userService.effectiveAuthorities(USER)).thenReturn(Set.of("ROLE_USER",
                Permissions.USER_READ, Permissions.GROUP_UPDATE, Permissions.GROUP_READ));

        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_USER", Permissions.USER_READ)), Set.of()), List.of());

        // Exactly, not contains: this list answers "is this person over-privileged", so the failure direction
        // that matters is it being too LARGE — a subset assertion is blind to exactly that. The ROLE_ name drops.
        assertThat(detail.effectivePermissions()).containsExactlyInAnyOrder(
                Permissions.USER_READ, Permissions.GROUP_UPDATE, Permissions.GROUP_READ);
    }

    /**
     * The "why is this permission absent" answer: a permission the grants (role/group/direct) hand out but the
     * resolved authorities no longer carry was removed by a deny — it lands in {@code deniedPermissions}, while
     * {@code effectivePermissions} shows only what survives. Here the role grants user:update (implying
     * user:read), but the resolver returns only user:update — user:read was denied.
     */
    @Test
    void aGrantRemovedByADenyIsReportedAsDenied() {
        when(userService.effectiveAuthorities(USER)).thenReturn(Set.of(Permissions.USER_UPDATE));
        // The resolver's own verdicts, which is the change: the view used to infer "denied" by subtracting
        // one set from another and could name no tier for the refusal.
        when(userService.explainPermissions(USER)).thenReturn(List.of(
                new PermissionExplanation(Permissions.USER_UPDATE, true, DecisionReason.ALLOWED_AT_ROLE_LEVEL),
                new PermissionExplanation(Permissions.USER_READ, false, DecisionReason.DENIED_AT_USER_LEVEL)));

        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_X", Permissions.USER_UPDATE)), Set.of()), List.of());

        assertThat(detail.effectivePermissions()).containsExactly(Permissions.USER_UPDATE);
        assertThat(detail.deniedPermissions()).singleElement().satisfies(withheld -> {
            assertThat(withheld.permission()).isEqualTo(Permissions.USER_READ);
            assertThat(withheld.withheldBy()).isEqualTo(DecisionReason.DENIED_AT_USER_LEVEL);
        });
    }

    /**
     * The half the subtraction got wrong. A permission nobody ever granted is absent for a reason that was
     * never a refusal, and reporting it as one sends an administrator hunting a deny that does not exist.
     */
    @Test
    void aPermissionNobodyGrantedIsNotReportedAsDenied() {
        when(userService.effectiveAuthorities(USER)).thenReturn(Set.of(Permissions.USER_UPDATE));
        when(userService.explainPermissions(USER)).thenReturn(List.of(
                new PermissionExplanation(Permissions.USER_UPDATE, true, DecisionReason.ALLOWED_AT_ROLE_LEVEL),
                new PermissionExplanation(Permissions.USER_DELETE, false, DecisionReason.NO_LEVEL_SPOKE)));

        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_X", Permissions.USER_UPDATE)), Set.of()), List.of());

        assertThat(detail.deniedPermissions()).isEmpty();
    }

    /** With no deny the effective set carries every grant, so nothing is reported as denied. */
    @Test
    void withNoDenyNothingIsReportedAsDenied() {
        when(userService.effectiveAuthorities(USER)).thenReturn(Set.of(Permissions.USER_READ, Permissions.USER_UPDATE));
        when(userService.explainPermissions(USER)).thenReturn(List.of(
                new PermissionExplanation(Permissions.USER_READ, true, DecisionReason.ALLOWED_AT_ROLE_LEVEL),
                new PermissionExplanation(Permissions.USER_UPDATE, true, DecisionReason.ALLOWED_AT_ROLE_LEVEL)));

        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_X", Permissions.USER_UPDATE)), Set.of()), List.of());

        assertThat(detail.deniedPermissions()).isEmpty();
    }

    /** A surviving wildcard grant is shown with its concrete members, matching what the login principal carries. */
    @Test
    void aWildcardEffectiveAuthorityIsShownWithItsMembers() {
        when(userService.effectiveAuthorities(USER)).thenReturn(Set.of("user:*",
                Permissions.USER_READ, Permissions.USER_CREATE, Permissions.USER_UPDATE, Permissions.USER_DELETE));

        UserDetailView detail = detailOf(
                account(Set.of(role("ROLE_ORG_ADMIN", "user:*")), Set.of()), List.of());

        assertThat(detail.effectivePermissions()).containsExactlyInAnyOrder("user:*",
                Permissions.USER_READ, Permissions.USER_CREATE, Permissions.USER_UPDATE, Permissions.USER_DELETE);
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
        return new SessionMetadata("handle-" + sessionId, sessionId, USERNAME, "Firefox", ip, Instant.EPOCH, Instant.EPOCH);
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
