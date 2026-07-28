package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.LastAdminRetractionGuard;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.mapping.internal.domain.MappingRule;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.user.group.UserGroupService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The last-administrator guard on the ABAC retraction path, and the shape that makes its ordering safe.
 *
 * <p>A retraction reaches {@code RoleService.removeMember} directly — the domain service, below every guard the
 * console path goes through — so a tenant whose {@code ROLE_ORG_ADMIN} is conferred by a mapping rule could
 * lose its last administrator to an attribute edit, and nothing else would notice.
 */
@ExtendWith(MockitoExtension.class)
class RetractionAdminGuardTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET_ROLE = UUID.randomUUID();
    private static final UUID TARGET_GROUP = UUID.randomUUID();
    private static final UUID DELEGATED_ROLE = UUID.randomUUID();

    @Mock private LastAdminRetractionGuard invariant;
    @Mock private UserGroupService userGroups;
    @Mock private OrgTierGuard tierGuard;
    @Mock private MappingAuditTrail trail;

    private RetractionAdminGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RetractionAdminGuard(invariant, userGroups, tierGuard, trail);
        lenient().when(tierGuard.currentTier()).thenReturn(ORG);
        lenient().when(userGroups.delegatedRoleIds(any())).thenReturn(Map.of());
    }

    @Test
    void aScopeThatNeedsGuardingRecountsAndPropagatesTheRefusal() {
        when(invariant.retractionWouldNeedGuarding(any(), eq(ORG))).thenReturn(true);
        ConflictException refused = ConflictException.of("admin.lastAdmin");
        doThrow(refused).when(invariant).ensureRetractionRetainsAdmin(ORG);

        RetractionScope scope = guard.before(List.of(rule(MappingTargetKind.ROLE, TARGET_ROLE)));

        assertThatThrownBy(() -> scope.assertTierKeepsAnAdmin(Set.of(TARGET_ROLE)))
                .isInstanceOf(ConflictException.class);
        // The one row that must outlive the rollback it describes — everything else the transaction wrote goes.
        verify(trail).refusalNow(ORG, 1, refused);
    }

    /**
     * The pre-state narrowing, read BEFORE the mutation. The recount answers "is there an admin NOW", which in
     * a tier that had none — a fresh tenant whose invited admin is still disabled — refuses every admin-bearing
     * retraction from then on, in the retraction's name. Once the memberships are gone that is undecidable.
     */
    @Test
    void aScopeThatDoesNotNeedGuardingNeverRecounts() {
        when(invariant.retractionWouldNeedGuarding(any(), eq(ORG))).thenReturn(false);

        RetractionScope scope = guard.before(List.of(rule(MappingTargetKind.ROLE, TARGET_ROLE)));
        scope.assertTierKeepsAnAdmin(Set.of(TARGET_ROLE));

        verify(invariant, never()).ensureRetractionRetainsAdmin(any());
        verify(trail, never()).refusalNow(any(), anyInt(), any());
    }

    /** A pass that retracted nothing has nothing to answer for, however the pre-state came out. */
    @Test
    void aScopeSpentOnAnEmptyRetractionNeverRecounts() {
        lenient().when(invariant.retractionWouldNeedGuarding(any(), eq(ORG))).thenReturn(true);

        RetractionScope scope = guard.before(List.of(rule(MappingTargetKind.ROLE, TARGET_ROLE)));
        assertThatCode(() -> scope.assertTierKeepsAnAdmin(Set.of())).doesNotThrowAnyException();

        verify(invariant, never()).ensureRetractionRetainsAdmin(any());
    }

    /** What a GROUP target takes away is the roles it delegates, not the group id — a deny rides on the role. */
    @Test
    void aGroupTargetIsWeighedByTheRolesItDelegates() {
        when(userGroups.delegatedRoleIds(Set.of(TARGET_GROUP))).thenReturn(Map.of(TARGET_GROUP, Set.of(DELEGATED_ROLE)));
        when(invariant.retractionWouldNeedGuarding(Set.of(TARGET_GROUP, DELEGATED_ROLE), ORG)).thenReturn(true);

        RetractionScope scope = guard.before(List.of(rule(MappingTargetKind.GROUP, TARGET_GROUP)));
        scope.assertTierKeepsAnAdmin(Set.of(TARGET_GROUP));

        verify(invariant).ensureRetractionRetainsAdmin(ORG);
    }

    /** A RESOURCE_MEMBER rule confers no authority, so it is not weighed at all. */
    @Test
    void aResourceMembershipTargetIsNotWeighed() {
        when(invariant.retractionWouldNeedGuarding(Set.of(), ORG)).thenReturn(false);

        guard.before(List.of(rule(MappingTargetKind.RESOURCE_MEMBER, TARGET_ROLE)));

        verify(userGroups, never()).delegatedRoleIds(any());
    }

    private MappingRule rule(MappingTargetKind kind, UUID targetId) {
        MappingRule rule = MappingRule.of(kind, targetId, ORG, UUID.randomUUID());
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        return rule;
    }
}
