package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceRoleTier;
import com.example.sso.user.role.Roles;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the Postgres-graph {@link ResourceScope}. Outside an HTTP request the managed set is
 * computed directly (no memo), and {@code isUnscoped} matches the effective super-admin model (direct
 * ROLE_ADMIN). The managed-set walk must bind the tier NAME, never inline the enum — asserted via
 * {@code verify} on the exact {@code ADMIN} tier argument.
 */
@ExtendWith(MockitoExtension.class)
class ResourceScopeImplTest {

    @Mock
    private ResourceRepository resources;
    @Mock
    private UserService users;
    @Mock
    private UserGroupService userGroups;

    @InjectMocks
    private ResourceScopeImpl scope;

    private final UUID actor = UUID.randomUUID();

    @Test
    void directAdminRoleIsUnscoped() {
        when(users.hasRole(actor, Roles.ADMIN)).thenReturn(true);

        assertThat(scope.isUnscoped(actor)).isTrue();
    }

    @Test
    void aUserWithNoAdminRoleNorAdminGroupIsScoped() {
        when(users.hasRole(actor, Roles.ADMIN)).thenReturn(false);
        when(userGroups.membershipsForUser(actor)).thenReturn(List.of());

        assertThat(scope.isUnscoped(actor)).isFalse();
    }

    /**
     * Within one request the answer is computed ONCE.
     *
     * <p>{@code isUnscoped} was the only scope question here that was not memoized, and it is the expensive
     * one: it reads the actor's role and then every group they belong to. Authorization asks it once per
     * object being judged — a bulk admin action over D groups asked it D times, each re-reading the same
     * actor, all inside one read-only transaction.
     */
    @Test
    void isUnscopedIsAnsweredOncePerRequest() {
        withRequestContext(() -> {
            when(users.hasRole(actor, Roles.ADMIN)).thenReturn(false);
            when(userGroups.membershipsForUser(actor)).thenReturn(List.of());

            assertThat(scope.isUnscoped(actor)).isFalse();
            assertThat(scope.isUnscoped(actor)).isFalse();
            assertThat(scope.isUnscoped(actor)).isFalse();

            verify(users, times(1)).hasRole(actor, Roles.ADMIN);
            verify(userGroups, times(1)).membershipsForUser(actor);
        });
    }

    /** A different actor is a different question — the memo is per actor, not per request. */
    @Test
    void eachActorIsJudgedOnTheirOwn() {
        UUID other = UUID.randomUUID();
        withRequestContext(() -> {
            when(users.hasRole(actor, Roles.ADMIN)).thenReturn(true);
            when(users.hasRole(other, Roles.ADMIN)).thenReturn(false);
            when(userGroups.membershipsForUser(other)).thenReturn(List.of());

            assertThat(scope.isUnscoped(actor)).isTrue();
            assertThat(scope.isUnscoped(other)).isFalse();
        });
    }

    /** Outside a request there is nowhere to memoize, so every call resolves — the pre-existing behaviour. */
    @Test
    void withoutARequestEveryCallResolves() {
        when(users.hasRole(actor, Roles.ADMIN)).thenReturn(false);
        when(userGroups.membershipsForUser(actor)).thenReturn(List.of());

        scope.isUnscoped(actor);
        scope.isUnscoped(actor);

        verify(users, times(2)).hasRole(actor, Roles.ADMIN);
    }

    private void withRequestContext(Runnable body) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            body.run();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void managedResourceIdsWalkTheGraphBoundToTheAdminTierName() {
        UUID managed = UUID.randomUUID();
        when(resources.findManagedResourceIds(actor, ResourceRoleTier.ADMIN.name()))
                .thenReturn(Set.of(managed));

        assertThat(scope.managedResourceIds(actor)).containsExactly(managed);
        verify(resources).findManagedResourceIds(actor, ResourceRoleTier.ADMIN.name());
    }

    @Test
    void reachesDelegatesToTheRepository() {
        UUID ancestor = UUID.randomUUID();
        UUID descendant = UUID.randomUUID();
        when(resources.reaches(ancestor, descendant)).thenReturn(true);

        assertThat(scope.reaches(ancestor, descendant)).isTrue();
    }
}
