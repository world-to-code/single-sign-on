package com.example.sso.portal.internal.catalog.application;


import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyResolver;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.access.AppAccessQuery;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.AppAssignment;
import com.example.sso.portal.internal.catalog.domain.AppAssignment.SubjectType;
import com.example.sso.portal.internal.catalog.domain.AppAssignmentRepository;
import com.example.sso.portal.internal.catalog.domain.AppPolicy;
import com.example.sso.portal.internal.catalog.domain.AppPolicyRepository;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppAccessResolver}: subject matching (USER/ROLE/GROUP), highest-priority policy
 * resolution across app-level and per-subject assignments, the factor + step-up freshness gate in
 * {@code appAccess}, and the {@code setAppPolicy} repository writes.
 */
class AppAccessResolverTest {

    private static final AppType APP_TYPE = AppType.OIDC;
    private static final String APP_ID = "app1";

    private AppAssignmentRepository assignments;
    private AppPolicyRepository appPolicies;
    private UserGroupService userGroups;
    private AuthPolicyResolver authPolicies;
    private AuthPolicyEvaluator evaluator;
    private AppAccessResolver resolver;

    @BeforeEach
    void setUp() {
        assignments = mock(AppAssignmentRepository.class);
        appPolicies = mock(AppPolicyRepository.class);
        userGroups = mock(UserGroupService.class);
        authPolicies = mock(AuthPolicyResolver.class);
        evaluator = mock(AuthPolicyEvaluator.class);
        resolver = new AppAccessResolver(assignments, appPolicies, authPolicies, evaluator, userGroups);
    }

    // --- resolveAppPolicy / subjectMatches (exercised through appAccess) ---

    @Test
    void noResolvablePolicyGrantsImmediateAccess() {
        UserAccount user = user(UUID.randomUUID(), Set.of(), List.of());
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of());
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isTrue();
        assertThat(access.pendingFactors()).isEmpty();
        verify(authPolicies, never()).highestPriorityEnabled(anyCollection());
    }

    @Test
    void userSubjectAssignmentContributesItsPolicy() {
        UUID userId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UserAccount user = user(userId, Set.of(), List.of());
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID))
                .thenReturn(List.of(new AppAssignment(APP_TYPE, APP_ID, SubjectType.USER, userId, policyId)));
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        resolver.appAccess(query(user, null));

        assertThat(capturedCandidateIds()).containsExactly(policyId);
    }

    @Test
    void roleSubjectAssignmentMatchesViaUserRoles() {
        UUID roleId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UserAccount user = user(UUID.randomUUID(), Set.of(roleId), List.of());
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID))
                .thenReturn(List.of(new AppAssignment(APP_TYPE, APP_ID, SubjectType.ROLE, roleId, policyId)));
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        resolver.appAccess(query(user, null));

        assertThat(capturedCandidateIds()).containsExactly(policyId);
    }

    @Test
    void groupSubjectAssignmentMatchesViaGroupMembership() {
        UUID groupId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UserAccount user = user(UUID.randomUUID(), Set.of(), List.of(groupId));
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID))
                .thenReturn(List.of(new AppAssignment(APP_TYPE, APP_ID, SubjectType.GROUP, groupId, policyId)));
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        resolver.appAccess(query(user, null));

        assertThat(capturedCandidateIds()).containsExactly(policyId);
    }

    @Test
    void nonMatchingSubjectAssignmentIsIgnored() {
        UserAccount user = user(UUID.randomUUID(), Set.of(), List.of());
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.USER, UUID.randomUUID(), UUID.randomUUID())));
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isTrue();
        verify(authPolicies, never()).highestPriorityEnabled(anyCollection());
    }

    @Test
    void assignmentWithoutRequiredPolicyIsIgnored() {
        UUID userId = UUID.randomUUID();
        UserAccount user = user(userId, Set.of(), List.of());
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.USER, userId, null)));
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isTrue();
        verify(authPolicies, never()).highestPriorityEnabled(anyCollection());
    }

    @Test
    void appLevelAndMatchingAssignmentPoliciesAreAllCandidates() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID appLevel = UUID.randomUUID();
        UserAccount user = user(userId, Set.of(roleId), List.of());
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of(
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.USER, userId, p1),
                new AppAssignment(APP_TYPE, APP_ID, SubjectType.ROLE, roleId, p2)));
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID))
                .thenReturn(Optional.of(new AppPolicy(APP_TYPE, APP_ID, appLevel)));

        resolver.appAccess(query(user, null));

        assertThat(capturedCandidateIds()).containsExactlyInAnyOrder(p1, p2, appLevel);
    }

    // --- appAccess factor / step-up freshness gate ---

    @Test
    void missingFactorReturnsPendingFactorsSortedByOrdinal() {
        UserAccount user = userWithAppPolicy();
        AuthPolicyStepView step = step(AuthFactor.TOTP, AuthFactor.PASSWORD);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.of(step));

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isFalse();
        assertThat(access.pendingFactors()).containsExactly("PASSWORD", "TOTP");
    }

    @Test
    void allFactorsHeldWithFreshStepUpGrantsAccess() {
        AuthPolicyView policy = userAppPolicyView();
        when(policy.getStepUpFreshnessMinutes()).thenReturn(5);
        // Non-empty steps, so a grant can ONLY come from the freshness branch (not the empty-steps shortcut).
        doReturn(List.of(step(AuthFactor.FIDO2))).when(policy).getSteps();
        UserAccount user = userWithAppPolicy(policy);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, Instant.now().minus(1, ChronoUnit.MINUTES)));

        assertThat(access.ready()).isTrue();
        assertThat(access.pendingFactors()).isEmpty();
    }

    @Test
    void allFactorsHeldButStaleStepUpRequiresTheFinalStep() {
        AuthPolicyView policy = userAppPolicyView();
        when(policy.getStepUpFreshnessMinutes()).thenReturn(5);
        AuthPolicyStepView first = step(AuthFactor.PASSWORD);
        AuthPolicyStepView last = step(AuthFactor.FIDO2);
        doReturn(List.of(first, last)).when(policy).getSteps();
        UserAccount user = userWithAppPolicy(policy);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, Instant.now().minus(10, ChronoUnit.MINUTES)));

        assertThat(access.ready()).isFalse();
        assertThat(access.pendingFactors()).containsExactly("FIDO2");
    }

    @Test
    void allFactorsHeldWithNoStepsGrantsAccessEvenWithoutFreshStepUp() {
        AuthPolicyView policy = userAppPolicyView();
        when(policy.getStepUpFreshnessMinutes()).thenReturn(5);
        when(policy.getSteps()).thenReturn(List.of());
        UserAccount user = userWithAppPolicy(policy);
        when(evaluator.currentStep(any(), any())).thenReturn(Optional.empty());

        AppAccess access = resolver.appAccess(query(user, null));

        assertThat(access.ready()).isTrue();
    }

    // --- setAppPolicy ---

    @Test
    void setAppPolicyWithBlankIdClearsWithoutSaving() {
        resolver.setAppPolicy(APP_TYPE, APP_ID, "  ");

        verify(appPolicies).deleteByAppTypeAndAppId(APP_TYPE, APP_ID);
        verify(appPolicies, never()).save(any());
    }

    @Test
    void setAppPolicyPersistsAnExistingPolicy() {
        UUID policyId = UUID.randomUUID();
        when(authPolicies.exists(policyId)).thenReturn(true);

        resolver.setAppPolicy(APP_TYPE, APP_ID, policyId.toString());

        verify(appPolicies).deleteByAppTypeAndAppId(APP_TYPE, APP_ID);
        verify(appPolicies).save(any(AppPolicy.class));
    }

    @Test
    void setAppPolicyRejectsAnUnknownPolicy() {
        UUID policyId = UUID.randomUUID();
        when(authPolicies.exists(policyId)).thenReturn(false);

        assertThatThrownBy(() -> resolver.setAppPolicy(APP_TYPE, APP_ID, policyId.toString()))
                .isInstanceOf(NotFoundException.class);
        verify(appPolicies, never()).save(any());
    }

    // --- helpers ---

    private AppAccessQuery query(UserAccount user, Instant lastAppStepUp) {
        return new AppAccessQuery(user, APP_TYPE, APP_ID, Set.of("FACTOR_PASSWORD"), lastAppStepUp);
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

    /** A user resolving to a single app-level policy backed by a fresh mock {@link AuthPolicyView}. */
    private UserAccount userWithAppPolicy() {
        return userWithAppPolicy(userAppPolicyView());
    }

    private UserAccount userWithAppPolicy(AuthPolicyView policy) {
        UserAccount user = user(UUID.randomUUID(), Set.of(), List.of());
        UUID policyId = UUID.randomUUID();
        when(assignments.findByAppTypeAndAppId(APP_TYPE, APP_ID)).thenReturn(List.of());
        when(appPolicies.findByAppTypeAndAppId(APP_TYPE, APP_ID))
                .thenReturn(Optional.of(new AppPolicy(APP_TYPE, APP_ID, policyId)));
        when(authPolicies.highestPriorityEnabled(anyCollection())).thenReturn(Optional.of(policy));
        return user;
    }

    private AuthPolicyView userAppPolicyView() {
        return mock(AuthPolicyView.class);
    }

    private AuthPolicyStepView step(AuthFactor... factors) {
        AuthPolicyStepView step = mock(AuthPolicyStepView.class);
        when(step.getAllowedFactors()).thenReturn(Set.of(factors));
        return step;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Collection<UUID> capturedCandidateIds() {
        ArgumentCaptor<Collection> captor = ArgumentCaptor.forClass(Collection.class);
        verify(authPolicies).highestPriorityEnabled(captor.capture());
        return captor.getValue();
    }
}
