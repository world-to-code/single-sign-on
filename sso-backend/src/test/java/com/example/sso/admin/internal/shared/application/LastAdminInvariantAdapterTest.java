package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two narrowings that decide whether an ABAC retraction has to answer to the last-admin invariant. Both
 * are here rather than in the guard because both are questions about THIS retraction, and the guard only
 * knows how to count a tier.
 */
@ExtendWith(MockitoExtension.class)
class LastAdminInvariantAdapterTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OPS = UUID.randomUUID();

    @Mock private LastAdminGuard guard;
    @Mock private RoleService roles;

    @InjectMocks private LastAdminInvariantAdapter adapter;

    /**
     * The capability, not the name. {@code ROLE_ORG_ADMIN}'s permission set is editable, so an org may move
     * {@code user:update} onto an ordinary role like this one — and the recount turns on {@code user:update}
     * alone. Narrowing by name skipped the guard for exactly that role, so the tier could lose its last
     * effective administrator to an attribute edit with no 409 and no way back except a platform super.
     */
    @Test
    void aRoleCarryingTheCapabilityUnderAnOrdinaryNameStillNeedsGuarding() {
        when(roles.effectivePermissionNames(Set.of(OPS))).thenReturn(Set.of(Permissions.USER_UPDATE));
        when(guard.tierHasAdmin(ORG)).thenReturn(true);

        assertThat(adapter.retractionWouldNeedGuarding(Set.of(OPS), ORG)).isTrue();
    }

    /** And a role that cannot subtract the capability is not guarded — nor does it pay for the tier read. */
    @Test
    void aRoleWithoutTheCapabilityIsNotGuardedAndTheTierIsNotEvenCounted() {
        when(roles.effectivePermissionNames(Set.of(OPS))).thenReturn(Set.of(Permissions.USER_READ));

        assertThat(adapter.retractionWouldNeedGuarding(Set.of(OPS), ORG)).isFalse();
        verify(guard, never()).tierHasAdmin(any());
    }

    /**
     * The pre-state narrowing. A tier with no administrator BEFORE the retraction cannot have lost one to it,
     * and the post-mutation recount cannot tell the difference — so without this read, every admin-bearing
     * retraction in an already-broken tenant is refused forever, in the retraction's name.
     */
    @Test
    void aTierThatAlreadyHadNoAdministratorIsNotGuarded() {
        when(roles.effectivePermissionNames(Set.of(OPS))).thenReturn(Set.of(Permissions.USER_UPDATE));
        when(guard.tierHasAdmin(ORG)).thenReturn(false);

        assertThat(adapter.retractionWouldNeedGuarding(Set.of(OPS), ORG)).isFalse();
    }

    /** Nothing retracted, nothing to ask — and no query paid for either. */
    @Test
    void anEmptyRetractionAsksTheRoleServiceNothing() {
        assertThat(adapter.retractionWouldNeedGuarding(Set.of(), ORG)).isFalse();

        verify(roles, never()).effectivePermissionNames(any());
        verify(guard, never()).tierHasAdmin(any());
    }
}
