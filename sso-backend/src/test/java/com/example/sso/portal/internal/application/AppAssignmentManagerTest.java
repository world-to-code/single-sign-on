package com.example.sso.portal.internal.application;

import com.example.sso.portal.AppAssignmentView;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.AssignAppRequest;
import com.example.sso.portal.internal.domain.AppAssignment;
import com.example.sso.portal.internal.domain.AppAssignment.SubjectType;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.portal.internal.domain.AppAssignmentRepository;
import com.example.sso.shared.IdName;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppAssignmentManager}: subject matching for {@code hasAssignment} (USER/ROLE/
 * GROUP), the assign/unassign repository writes, and the reverse-lookup projections ({@code appsForUser},
 * {@code assignmentsForApp}) that resolve app and subject display names from the {@link AppCatalog}.
 */
class AppAssignmentManagerTest {

    private static final AppType APP_TYPE = AppType.OIDC;
    private static final String APP_ID = "app1";

    private AppAssignmentRepository assignments;
    private UserService users;
    private RoleService roles;
    private UserGroupService userGroups;
    private AppCatalog catalog;
    private OrgContext orgContext;
    private AppAssignmentManager manager;
    private AuthPolicyResolver authPolicies;

    @BeforeEach
    void setUp() {
        assignments = mock(AppAssignmentRepository.class);
        users = mock(UserService.class);
        roles = mock(RoleService.class);
        userGroups = mock(UserGroupService.class);
        catalog = mock(AppCatalog.class);
        orgContext = mock(OrgContext.class);
        authPolicies = mock(AuthPolicyResolver.class);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty()); // platform/global tier by default
        // REAL guard over the mocked context so tier-ownership logic is exercised, not stubbed.
        manager = new AppAssignmentManager(assignments, users, roles, userGroups, catalog,
                new OrgTierGuard(orgContext), authPolicies);
    }

    // --- hasAssignment ---

    @Test
    void hasAssignmentTrueForDirectUserAssignment() {
        UUID userId = UUID.randomUUID();
        UserAccount user = user(userId, Set.of(), List.of());
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                APP_TYPE, APP_ID, SubjectType.USER, userId)).thenReturn(true);

        assertThat(manager.hasAssignment(user, APP_TYPE, APP_ID)).isTrue();
        verify(assignments, never()).findByAppTypeAndAppId(any(), any());
    }

    @Test
    void hasAssignmentTrueWhenARoleMatches() {
        UUID roleId = UUID.randomUUID();
        UserAccount user = user(UUID.randomUUID(), Set.of(roleId), List.of());
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(any(), any(), any(), any()))
                .thenReturn(false);
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.ROLE, roleId, null)));

        assertThat(manager.hasAssignment(user, APP_TYPE, APP_ID)).isTrue();
    }

    @Test
    void hasAssignmentTrueWhenAGroupMatches() {
        UUID groupId = UUID.randomUUID();
        UserAccount user = user(UUID.randomUUID(), Set.of(), List.of(groupId));
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(any(), any(), any(), any()))
                .thenReturn(false);
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.GROUP, groupId, null)));

        assertThat(manager.hasAssignment(user, APP_TYPE, APP_ID)).isTrue();
    }

    @Test
    void hasAssignmentFalseWhenNothingMatches() {
        UserAccount user = user(UUID.randomUUID(), Set.of(UUID.randomUUID()), List.of());
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(any(), any(), any(), any()))
                .thenReturn(false);
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.ROLE, UUID.randomUUID(), null)));

        assertThat(manager.hasAssignment(user, APP_TYPE, APP_ID)).isFalse();
    }

    // --- assign / unassign ---

    @Test
    void assignPersistsAndProjectsTheAssignment() {
        UUID subjectId = UUID.randomUUID();
        AssignAppRequest request = new AssignAppRequest("OIDC", APP_ID, "USER", subjectId.toString(), null);
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                APP_TYPE, APP_ID, SubjectType.USER, subjectId)).thenReturn(false);

        AppAssignment saved = mock(AppAssignment.class);
        UUID assignmentId = UUID.randomUUID();
        when(saved.getId()).thenReturn(assignmentId);
        when(saved.getAppType()).thenReturn(APP_TYPE);
        when(saved.getAppId()).thenReturn(APP_ID);
        when(saved.getSubjectType()).thenReturn(SubjectType.USER);
        when(saved.getSubjectId()).thenReturn(subjectId);
        when(saved.getRequiredPolicyId()).thenReturn(null);
        when(assignments.save(any(AppAssignment.class))).thenReturn(saved);
        IdName alice = idName(subjectId, "alice");
        when(users.idNames(any())).thenReturn(List.of(alice));
        when(catalog.index()).thenReturn(Map.of()); // no app in the catalog -> appName falls back to the id

        AppAssignmentView view = manager.assign(request);

        ArgumentCaptor<AppAssignment> built = ArgumentCaptor.forClass(AppAssignment.class);
        verify(assignments).save(built.capture());
        assertThat(built.getValue().getAppType()).isEqualTo(APP_TYPE);
        assertThat(built.getValue().getAppId()).isEqualTo(APP_ID);
        assertThat(built.getValue().getSubjectType()).isEqualTo(SubjectType.USER);
        assertThat(built.getValue().getSubjectId()).isEqualTo(subjectId);
        assertThat(built.getValue().getRequiredPolicyId()).isNull();
        assertThat(built.getValue().getOrgId()).isNull(); // platform tier -> a global assignment
        assertThat(view.id()).isEqualTo(assignmentId.toString());
        assertThat(view.appType()).isEqualTo("OIDC");
        assertThat(view.subjectType()).isEqualTo("USER");
        assertThat(view.subjectId()).isEqualTo(subjectId.toString());
        assertThat(view.subjectName()).isEqualTo("alice");
        assertThat(view.appName()).isEqualTo(APP_ID); // empty index -> falls back to the request's appId
    }

    @Test
    void assignParsesAndPersistsANonNullRequiredPolicyId() {
        UUID subjectId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        AssignAppRequest request = new AssignAppRequest("OIDC", APP_ID, "ROLE", subjectId.toString(), policyId.toString());
        when(authPolicies.exists(policyId)).thenReturn(true);
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                APP_TYPE, APP_ID, SubjectType.ROLE, subjectId)).thenReturn(false);
        AppAssignment saved = assignment(SubjectType.ROLE, subjectId);
        when(assignments.save(any(AppAssignment.class))).thenReturn(saved);
        IdName admins = idName(subjectId, "admins");
        when(roles.idNames(any())).thenReturn(List.of(admins));
        when(catalog.index()).thenReturn(Map.of());

        manager.assign(request);

        ArgumentCaptor<AppAssignment> built = ArgumentCaptor.forClass(AppAssignment.class);
        verify(assignments).save(built.capture());
        assertThat(built.getValue().getRequiredPolicyId()).isEqualTo(policyId);
    }

    @Test
    void assignStampsTheCallersTierOntoTheAssignment() {
        UUID orgA = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        AssignAppRequest request = new AssignAppRequest("OIDC", APP_ID, "ROLE", subjectId.toString(), null);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                APP_TYPE, APP_ID, SubjectType.ROLE, subjectId)).thenReturn(false);
        AppAssignment saved = assignment(SubjectType.ROLE, subjectId);
        IdName admins = idName(subjectId, "admins");
        when(assignments.save(any(AppAssignment.class))).thenReturn(saved);
        when(roles.idNames(any())).thenReturn(List.of(admins));
        when(catalog.index()).thenReturn(Map.of());

        manager.assign(request);

        ArgumentCaptor<AppAssignment> built = ArgumentCaptor.forClass(AppAssignment.class);
        verify(assignments).save(built.capture());
        assertThat(built.getValue().getOrgId()).isEqualTo(orgA); // tenant tier -> confined to that org
    }

    @Test
    void assignRejectsADuplicate() {
        UUID subjectId = UUID.randomUUID();
        AssignAppRequest request = new AssignAppRequest("OIDC", APP_ID, "USER", subjectId.toString(), null);
        when(assignments.existsByAppTypeAndAppIdAndSubjectTypeAndSubjectId(
                APP_TYPE, APP_ID, SubjectType.USER, subjectId)).thenReturn(true);

        assertThatThrownBy(() -> manager.assign(request)).isInstanceOf(ConflictException.class);
        verify(assignments, never()).save(any());
    }

    @Test
    void unassignDeletesAnInTierAssignment() {
        UUID id = UUID.randomUUID();
        AppAssignment assignment = assignment(SubjectType.USER, UUID.randomUUID()); // global (orgId null) == null tier
        when(assignments.findById(id)).thenReturn(Optional.of(assignment));

        manager.unassign(id);

        verify(assignments).delete(assignment);
    }

    @Test
    void unassignRejectsAMissingAssignment() {
        UUID id = UUID.randomUUID();
        when(assignments.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.unassign(id)).isInstanceOf(NotFoundException.class);
        verify(assignments, never()).delete(any(AppAssignment.class));
    }

    @Test
    void unassignRefusesAnotherTenantsAssignmentAsNotFound() {
        UUID id = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        AppAssignment other = mock(AppAssignment.class);
        when(other.getOrgId()).thenReturn(orgB);
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgA));
        when(assignments.findById(id)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> manager.unassign(id)).isInstanceOf(NotFoundException.class);
        verify(assignments, never()).delete(any(AppAssignment.class));
    }

    // --- reverse-lookup projections ---

    @Test
    void appsForUserResolvesDirectAssignmentsToCatalogAppsSortedByName() {
        UUID userId = UUID.randomUUID();
        UserAccount user = user(userId, Set.of(), List.of());
        when(assignments.findBySubjectTypeAndSubjectId(SubjectType.USER, userId)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, "zebra", SubjectType.USER, userId, null),
                new AppAssignment(APP_TYPE, "alpha", SubjectType.USER, userId, null)));
        ApplicationView zebra = appView("zebra", "Zebra");
        ApplicationView alpha = appView("alpha", "Alpha");
        when(catalog.index()).thenReturn(Map.of(
                AppKey.of(APP_TYPE, "zebra"), zebra,
                AppKey.of(APP_TYPE, "alpha"), alpha));

        List<ApplicationView> apps = manager.appsForUser(user);

        assertThat(apps).containsExactly(alpha, zebra); // sorted by name, case-insensitive
    }

    @Test
    void assignmentsForAppProjectsBatchResolvedSubjectNames() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        ApplicationView appOne = appView(APP_ID, "App One");
        when(catalog.index()).thenReturn(Map.of(AppKey.of(APP_TYPE, APP_ID), appOne));
        AppAssignment userAssignment = assignment(SubjectType.USER, userId);
        AppAssignment roleAssignment = assignment(SubjectType.ROLE, roleId);
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(userAssignment, roleAssignment));
        IdName aliceName = idName(userId, "alice");
        IdName adminsName = idName(roleId, "admins");
        when(users.idNames(any())).thenReturn(List.of(aliceName));
        when(roles.idNames(any())).thenReturn(List.of(adminsName));

        List<AppAssignmentView> views = manager.assignmentsForApp(APP_TYPE, APP_ID);

        assertThat(views).hasSize(2);
        assertThat(views).allSatisfy(v -> assertThat(v.appName()).isEqualTo("App One"));
        assertThat(views).extracting(AppAssignmentView::subjectName)
                .containsExactlyInAnyOrder("alice", "admins");
    }

    @Test
    void appsForGroupResolvesGroupAssignmentsToCatalogAppsSortedByName() {
        UUID groupId = UUID.randomUUID();
        when(assignments.findBySubjectTypeAndSubjectId(SubjectType.GROUP, groupId)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, "zebra", SubjectType.GROUP, groupId, null),
                new AppAssignment(APP_TYPE, "alpha", SubjectType.GROUP, groupId, null)));
        ApplicationView zebra = appView("zebra", "Zebra");
        ApplicationView alpha = appView("alpha", "Alpha");
        when(catalog.index()).thenReturn(Map.of(
                AppKey.of(APP_TYPE, "zebra"), zebra,
                AppKey.of(APP_TYPE, "alpha"), alpha));

        assertThat(manager.appsForGroup(groupId)).containsExactly(alpha, zebra); // sorted by name
    }

    @Test
    void appsForUserUnionsDirectRoleAndGroupAssignmentsAndDeduplicates() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UserAccount user = user(userId, Set.of(roleId), List.of(groupId));
        when(assignments.findBySubjectTypeAndSubjectId(SubjectType.USER, userId)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, "direct", SubjectType.USER, userId, null),
                new AppAssignment(APP_TYPE, "shared", SubjectType.USER, userId, null)));
        when(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.ROLE, Set.of(roleId))).thenReturn(List.of(
                new AppAssignment(APP_TYPE, "shared", SubjectType.ROLE, roleId, null))); // duplicate of the USER one
        when(assignments.findBySubjectTypeAndSubjectIdIn(SubjectType.GROUP, Set.of(groupId))).thenReturn(List.of(
                new AppAssignment(APP_TYPE, "grouped", SubjectType.GROUP, groupId, null),
                new AppAssignment(APP_TYPE, "missing", SubjectType.GROUP, groupId, null))); // not in the catalog
        ApplicationView direct = appView("direct", "Direct");
        ApplicationView shared = appView("shared", "Shared");
        ApplicationView grouped = appView("grouped", "Grouped");
        when(catalog.index()).thenReturn(Map.of(
                AppKey.of(APP_TYPE, "direct"), direct,
                AppKey.of(APP_TYPE, "shared"), shared,
                AppKey.of(APP_TYPE, "grouped"), grouped)); // "missing" absent -> filtered out

        List<ApplicationView> apps = manager.appsForUser(user);

        // union across USER/ROLE/GROUP, deduped (shared once), unknown key dropped, sorted by name
        assertThat(apps).containsExactly(direct, grouped, shared);
    }

    // --- helpers ---

    /** A persisted-looking assignment (non-null id) for projection tests. */
    private AppAssignment assignment(SubjectType type, UUID subjectId) {
        AppAssignment a = mock(AppAssignment.class);
        when(a.getId()).thenReturn(UUID.randomUUID());
        when(a.getAppType()).thenReturn(APP_TYPE);
        when(a.getAppId()).thenReturn(APP_ID);
        when(a.getSubjectType()).thenReturn(type);
        when(a.getSubjectId()).thenReturn(subjectId);
        return a;
    }

    private UserAccount user(UUID id, Set<UUID> roleIds, List<UUID> groupIds) {
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(id);
        Set<RoleRef> refs = roleIds.stream().map(rid -> {
            RoleRef ref = mock(RoleRef.class);
            when(ref.getId()).thenReturn(rid);
            return ref;
        }).collect(Collectors.toSet());
        doReturn(refs).when(user).getRoles();
        when(userGroups.groupIdsOf(id)).thenReturn(Set.copyOf(groupIds));
        return user;
    }

    private ApplicationView appView(String id, String name) {
        return new ApplicationView(id, APP_TYPE.name(), name, "/launch/" + id, false, null, null);
    }

    private IdName idName(UUID id, String name) {
        IdName idName = mock(IdName.class);
        when(idName.getId()).thenReturn(id);
        when(idName.getName()).thenReturn(name);
        return idName;
    }

    @Test
    void assignRejectsARequiredPolicyThatDoesNotExist() {
        // The per-assignment step-up policy is enforced at /oauth2/authorize; a dangling id silently resolves
        // to "no policy", so the app quietly loses the extra authentication the admin thought they configured.
        UUID policyId = UUID.randomUUID();
        when(authPolicies.exists(policyId)).thenReturn(false);
        AssignAppRequest request = new AssignAppRequest("OIDC", "app-1", "USER",
                UUID.randomUUID().toString(), policyId.toString());

        assertThatThrownBy(() -> manager.assign(request)).isInstanceOf(NotFoundException.class);
        verify(assignments, never()).save(any());
    }
}
