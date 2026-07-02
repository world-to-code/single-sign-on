package com.example.sso.resource;

import com.example.sso.resource.internal.application.ResourceGraphService;
import com.example.sso.resource.internal.application.ResourceScope;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrant;
import com.example.sso.resource.internal.domain.ResourceMember;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceRoleTier;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.NewUser;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 0 of the resource DAG: reachability, diamond dedup, cycle rejection, and the authorization
 * ports' subtree scoping (sibling isolation + super-admin bypass). Builds:
 *
 * <pre>
 *   dev ─→ backend ─→ shared        (diamond: dev also →
 *    └──→ frontend      ↑            shared directly)
 *    └──────────────────┘
 * </pre>
 *
 * with {@code backendLead} holding ADMIN on {@code backend} only. The shared Testcontainer DB has no
 * per-test rollback, so everything created here is deleted in {@link #cleanup()}.
 */
class ResourceGraphIT extends AbstractIntegrationTest {

    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceGraphService graph;
    @Autowired
    ResourceScope scope;
    @Autowired
    ResourceAuthorization resourceAuthz;
    @Autowired
    GroupAuthorization groupAuthz;
    @Autowired
    UserAuthorization userAuthz;
    @Autowired
    ApplicationAuthorization appAuthz;
    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;
    @Autowired
    PlatformTransactionManager txManager;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdGroups = new ArrayList<>();

    private UUID dev;
    private UUID backend;
    private UUID frontend;
    private UUID shared;
    private UUID backendLead;
    private UUID backendDev;
    private UUID frontendDev;
    private UUID backendGroup;
    private UUID frontendGroup;

    @BeforeEach
    void buildGraph() {
        ResourceType any = types.save(new ResourceType("ANY",
                Set.of(MemberType.RESOURCE, MemberType.GROUP, MemberType.APPLICATION, MemberType.USER)));

        backendLead = user("res-backendlead");
        backendDev = user("res-backenddev");
        frontendDev = user("res-frontenddev");
        backendGroup = group("Res-Backend", backendDev);
        frontendGroup = group("Res-Frontend", frontendDev);

        Resource devRes = new Resource("Res-Dev", any);
        Resource backendRes = new Resource("Res-BackendTeam", any);
        Resource frontendRes = new Resource("Res-FrontendTeam", any);
        Resource sharedRes = new Resource("Res-Shared", any);

        backendRes.grant(ResourceGrant.admin(backendLead));
        backendRes.attachMember(ResourceMember.group(backendGroup));
        backendRes.attachMember(ResourceMember.application("res-app-backend"));
        frontendRes.attachMember(ResourceMember.group(frontendGroup));
        frontendRes.attachMember(ResourceMember.application("res-app-frontend"));

        dev = resources.save(devRes).getId();
        backend = resources.save(backendRes).getId();
        frontend = resources.save(frontendRes).getId();
        shared = resources.save(sharedRes).getId();

        graph.attachChild(dev, backend);
        graph.attachChild(dev, frontend);
        graph.attachChild(backend, shared);
        graph.attachChild(dev, shared); // diamond: shared now has two parents
    }

    @AfterEach
    void cleanup() {
        resources.deleteAll(); // cascades edges/members/grants
        types.deleteAll();
        createdGroups.forEach(userGroups::delete);
        createdGroups.clear();
        createdUsers.forEach(userService::delete);
        createdUsers.clear();
    }

    @Test
    void managedResourceIdsWalksTheSubtreeAndDedupsTheDiamond() {
        assertThat(scope.managedResourceIds(backendLead)).containsExactlyInAnyOrder(backend, shared);
        assertThat(scope.managedResourceIds(backendDev)).isEmpty(); // no grant at all
    }

    @Test
    void reachabilityIsInclusiveAndDirectional() {
        assertThat(scope.reaches(dev, shared)).isTrue();
        assertThat(scope.reaches(backend, shared)).isTrue();
        assertThat(scope.reaches(backend, backend)).isTrue();   // inclusive
        assertThat(scope.reaches(backend, frontend)).isFalse(); // sibling
        assertThat(scope.reaches(shared, dev)).isFalse();       // never upward
    }

    @Test
    void edgeClosingACycleIsRejected() {
        assertThatThrownBy(() -> graph.attachChild(shared, dev)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> graph.attachChild(backend, dev)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> graph.attachChild(dev, dev)).isInstanceOf(ConflictException.class);
    }

    @Test
    void siblingSubtreesAreIsolatedAcrossAllPorts() {
        // Resources: the backend lead manages their subtree, never the sibling.
        assertThat(resourceAuthz.canManage(backendLead, backend)).isTrue();
        assertThat(resourceAuthz.canManage(backendLead, shared)).isTrue();
        assertThat(resourceAuthz.canManage(backendLead, frontend)).isFalse();
        assertThat(resourceAuthz.canManage(backendLead, dev)).isFalse(); // parent is above their grant

        // Groups / users / applications follow the resource scope.
        assertThat(groupAuthz.canManage(backendLead, backendGroup)).isTrue();
        assertThat(groupAuthz.canManage(backendLead, frontendGroup)).isFalse();
        assertThat(groupAuthz.scopedGroupIds(backendLead)).containsExactly(backendGroup);

        assertThat(userAuthz.canManage(backendLead, backendDev)).isTrue();   // via the backend group
        assertThat(userAuthz.canManage(backendLead, frontendDev)).isFalse();
        assertThat(userAuthz.scopedUserIds(backendLead)).containsExactly(backendDev);

        assertThat(appAuthz.canManage(backendLead, "res-app-backend")).isTrue();
        assertThat(appAuthz.canManage(backendLead, "res-app-frontend")).isFalse();
        assertThat(appAuthz.scopedAppIds(backendLead)).containsExactly("res-app-backend");
    }

    @Test
    void directUserMembersAreInScopeWithoutAGroup() {
        // A sub-resource inside the managed subtree carrying a DIRECT user member (no group involved).
        Resource directRes = new Resource("Res-Direct", types.findByNameFetchingKinds("ANY").orElseThrow());
        directRes.attachMember(ResourceMember.user(frontendDev));
        UUID direct = resources.save(directRes).getId();
        graph.attachChild(backend, direct);

        assertThat(userAuthz.canManage(backendLead, frontendDev)).isTrue();
        assertThat(userAuthz.scopedUserIds(backendLead)).containsExactlyInAnyOrder(backendDev, frontendDev);
    }

    @Test
    void superAdminBypassesScope() {
        UUID admin = userService.findByUsername("admin").orElseThrow().getId();

        assertThat(scope.isUnscoped(admin)).isTrue();
        assertThat(resourceAuthz.isUnscoped(admin)).isTrue();
        assertThat(groupAuthz.canManage(admin, frontendGroup)).isTrue();
        assertThat(userAuthz.canManage(admin, frontendDev)).isTrue();
        assertThat(appAuthz.canManage(admin, "res-app-frontend")).isTrue();

        assertThat(scope.isUnscoped(backendLead)).isFalse();
    }

    @Test
    void scopeWalksArbitrarilyDeepChains() {
        ResourceType any = types.findByNameFetchingKinds("ANY").orElseThrow();
        UUID deep = resources.save(new Resource("Res-Deep", any)).getId();
        UUID deeper = resources.save(new Resource("Res-Deeper", any)).getId();
        graph.attachChild(shared, deep);   // backend → shared → deep → deeper (3 below the grant)
        graph.attachChild(deep, deeper);

        assertThat(scope.managedResourceIds(backendLead))
                .containsExactlyInAnyOrder(backend, shared, deep, deeper);
        assertThat(scope.reaches(dev, deeper)).isTrue();
    }

    @Test
    void multipleAdminGrantsUnionDisjointSubtrees() {
        Resource island = new Resource("Res-Island", types.findByNameFetchingKinds("ANY").orElseThrow());
        island.grant(ResourceGrant.admin(backendLead));
        UUID islandId = resources.save(island).getId();

        assertThat(scope.managedResourceIds(backendLead))
                .containsExactlyInAnyOrder(backend, shared, islandId);
    }

    @Test
    void viewerTierConfersNoScopeInPhase0() {
        Resource watched = new Resource("Res-Watched", types.findByNameFetchingKinds("ANY").orElseThrow());
        watched.grant(ResourceGrant.viewer(backendLead));
        UUID watchedId = resources.save(watched).getId();

        assertThat(scope.managedResourceIds(backendLead)).doesNotContain(watchedId);
        assertThat(resourceAuthz.canManage(backendLead, watchedId)).isFalse();
        assertThat(resourceAuthz.canView(backendLead, watchedId)).isFalse(); // canView==canManage until Phase 2
    }

    @Test
    void revokingTheAdminGrantEmptiesTheScope() {
        inTx(() -> resources.findById(backend).orElseThrow().revoke(backendLead, ResourceRoleTier.ADMIN));

        assertThat(scope.managedResourceIds(backendLead)).isEmpty();
        assertThat(resourceAuthz.canManage(backendLead, backend)).isFalse();
        assertThat(groupAuthz.canManage(backendLead, backendGroup)).isFalse();
        assertThat(userAuthz.canManage(backendLead, backendDev)).isFalse();
    }

    @Test
    void detachChildRemovesTheSubtreeFromScope() {
        graph.detachChild(backend, shared);

        assertThat(scope.managedResourceIds(backendLead)).containsExactly(backend);
        assertThat(scope.reaches(dev, shared)).isTrue(); // still reachable via the direct dev→shared edge
    }

    @Test
    void deletingAResourceRevokesItsScopeViaCascade() {
        resources.deleteById(backend); // cascades its edges, members, and grants

        assertThat(scope.managedResourceIds(backendLead)).isEmpty();
        assertThat(scope.reaches(dev, shared)).isTrue(); // shared survives through its other parent
    }

    @Test
    void canViewMirrorsCanManageUntilPhase2() {
        assertThat(resourceAuthz.canView(backendLead, backend)).isTrue();
        assertThat(resourceAuthz.canView(backendLead, frontend)).isFalse();
        assertThat(groupAuthz.canView(backendLead, backendGroup)).isTrue();
        assertThat(groupAuthz.canView(backendLead, frontendGroup)).isFalse();
        assertThat(userAuthz.canView(backendLead, backendDev)).isTrue();
        assertThat(userAuthz.canView(backendLead, frontendDev)).isFalse();
        assertThat(appAuthz.canView(backendLead, "res-app-backend")).isTrue();
        assertThat(appAuthz.canView(backendLead, "res-app-frontend")).isFalse();
    }

    @Test
    void actorWithoutAnyGrantIsDeniedEverywhere() {
        assertThat(resourceAuthz.canManage(backendDev, backend)).isFalse();
        assertThat(groupAuthz.canManage(backendDev, backendGroup)).isFalse();
        assertThat(userAuthz.canManage(backendDev, backendDev)).isFalse(); // not even themselves
        assertThat(appAuthz.canManage(backendDev, "res-app-backend")).isFalse();
        assertThat(groupAuthz.scopedGroupIds(backendDev)).isEmpty();
        assertThat(userAuthz.scopedUserIds(backendDev)).isEmpty();
        assertThat(appAuthz.scopedAppIds(backendDev)).isEmpty();
    }

    @Test
    void groupDelegatedAdminRoleIsUnscoped() {
        // ROLE_ADMIN delegated through a group must make the member an effective super admin here,
        // matching the session authority model (SsoUserDetailsService) — not just a direct role.
        UUID adminsGroup = group("Res-Admins", backendDev);
        userGroups.setRoles(adminsGroup, Set.of("ROLE_ADMIN"));

        assertThat(scope.isUnscoped(backendDev)).isTrue();
        assertThat(groupAuthz.canManage(backendDev, frontendGroup)).isTrue();
    }

    @Test
    void regrantingPersistedGrantWithACatalogRoleReplacesTheRow() {
        // The PERSISTED half of the replace semantic: Hibernate must flush the old row's DELETE
        // before the new row's INSERT (same (resource,user,tier) PK, different role_id) — otherwise
        // a duplicate-PK violation. The role id must be real (FK to role).
        userGroups.setRoles(backendGroup, Set.of("ROLE_USER"));
        UUID catalogRole = userGroups.membershipsForUser(backendDev).stream()
                .filter(membership -> membership.groupId().equals(backendGroup)) // skip the seeded "All Users"
                .flatMap(membership -> membership.roles().stream())
                .findFirst().orElseThrow().getId();

        inTx(() -> resources.findById(backend).orElseThrow()
                .grant(new ResourceGrant(backendLead, ResourceRoleTier.ADMIN, catalogRole)));

        assertThat(scope.managedResourceIds(backendLead)).containsExactlyInAnyOrder(backend, shared);
    }

    @Test
    void duplicateEdgeAttachIsIdempotent() {
        graph.attachChild(dev, backend); // the edge already exists

        assertThat(scope.managedResourceIds(backendLead)).containsExactlyInAnyOrder(backend, shared);
    }

    @Test
    void attachingUnknownResourcesIsNotFound() {
        assertThatThrownBy(() -> graph.attachChild(dev, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> graph.attachChild(UUID.randomUUID(), dev)).isInstanceOf(NotFoundException.class);
        assertThat(scope.reaches(UUID.randomUUID(), dev)).isFalse(); // unknown ancestor: empty walk, no error
    }

    @Test
    void concurrentOppositeEdgeAttachesCannotFormACycle() throws Exception {
        // Regression for the check-then-act race the advisory lock closes: without it, both attaches
        // pass the reachability check and insert the two halves of a cycle.
        ResourceType any = types.findByNameFetchingKinds("ANY").orElseThrow();
        UUID x = resources.save(new Resource("Res-X", any)).getId();
        UUID y = resources.save(new Resource("Res-Y", any)).getId();

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Boolean> attachXY = () -> attachAfterBarrier(start, x, y);
        Callable<Boolean> attachYX = () -> attachAfterBarrier(start, y, x);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(attachXY, attachYX));
            boolean xyWon = results.get(0).get();
            boolean yxWon = results.get(1).get();

            assertThat(xyWon ^ yxWon).isTrue(); // exactly one attach wins, the other gets 409
        } finally {
            pool.shutdown();
        }
        assertThat(scope.reaches(x, y) && scope.reaches(y, x)).isFalse(); // and no cycle exists
    }

    @Test
    void managedScopeIsMemoizedPerRequestAndIsolatedPerUser() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            assertThat(scope.managedResourceIds(backendLead)).containsExactlyInAnyOrder(backend, shared);
            assertThat(scope.managedResourceIds(backendDev)).isEmpty(); // per-user keys: no bleed-over

            // Stale-by-design inside one request: a new grant shows up only on the next request.
            inTx(() -> resources.findById(frontend).orElseThrow().grant(ResourceGrant.admin(backendLead)));
            assertThat(scope.managedResourceIds(backendLead)).doesNotContain(frontend);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(scope.managedResourceIds(backendLead)).contains(frontend); // fresh outside the request
    }

    private boolean attachAfterBarrier(CyclicBarrier start, UUID parent, UUID child) throws Exception {
        start.await();
        try {
            graph.attachChild(parent, child);
            return true;
        } catch (ConflictException e) {
            return false;
        }
    }

    private void inTx(Runnable mutation) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> mutation.run());
    }

    private UUID user(String username) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }

    private UUID group(String name, UUID memberId) {
        UUID id = UUID.fromString(userGroups.create(new GroupSpec(name, null, null, Set.of(memberId))).id());
        createdGroups.add(id);
        return id;
    }
}
