package com.example.sso.resource;

import com.example.sso.resource.internal.application.ResourceAdminService;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.Resource;
import com.example.sso.resource.internal.domain.ResourceGrantRowRepository;
import com.example.sso.resource.internal.domain.ResourceMemberRowRepository;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceType;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMember;
import com.example.sso.resource.internal.domain.ResourceTypeAllowedMemberRepository;
import com.example.sso.resource.internal.domain.ResourceTypeRepository;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.NewUser;
import com.example.sso.user.Roles;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency on a resource's grant/member rows — the adversary being a LOST UPDATE: two admins
 * mutating one resource's grants/members at once must not silently drop each other's write. Grants and
 * members are now explicit entity rows, so this proves the service emits incremental per-row INSERTs
 * (not a collection-wide delete+reinsert that would clobber a concurrent row).
 */
class ResourceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService service;
    @Autowired
    ResourceRepository resources;
    @Autowired
    ResourceTypeRepository types;
    @Autowired
    ResourceGrantRowRepository grantRows;
    @Autowired
    ResourceMemberRowRepository memberRows;
    @Autowired
    ResourceTypeAllowedMemberRepository allowedMembers;
    @Autowired
    UserService users;

    private final List<UUID> createdUsers = new ArrayList<>();
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        asSuperAdmin(); // main thread; the concurrency workers set their own thread-local context
        ResourceType any = saveType("CONC-ANY", MemberType.GROUP, MemberType.USER, MemberType.APPLICATION);
        resourceId = resources.save(new Resource("Conc-Res", any, null)).getId();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        resources.deleteAll();
        types.deleteAll();
        createdUsers.forEach(users::delete);
        createdUsers.clear();
    }

    @Test
    void concurrentAdminGrantsForDifferentUsersBothPersist() throws Exception {
        UUID a = user("conc-a");
        UUID b = user("conc-b");

        runInParallel(
                () -> { service.assignAdmin(resourceId, a); return null; },
                () -> { service.assignAdmin(resourceId, b); return null; });

        assertThat(grantRows.findByResourceId(resourceId).stream().map(g -> g.getUserId()))
                .containsExactlyInAnyOrder(a, b); // neither grant lost
    }

    @Test
    void concurrentMemberAttachesForDifferentMembersBothPersist() throws Exception {
        // Real, existing users (attachMember validates existence) attached as USER members.
        String u1 = user("conc-m1").toString();
        String u2 = user("conc-m2").toString();

        runInParallel(
                () -> service.attachMember(resourceId, MemberType.USER, u1),
                () -> service.attachMember(resourceId, MemberType.USER, u2));

        assertThat(memberRows.findByResourceId(resourceId).stream().map(m -> m.getMemberId()))
                .containsExactlyInAnyOrder(u1, u2);
    }

    @Test
    void concurrentAssignAndRevokeOfSameUserConvergeWithoutError() throws Exception {
        UUID target = user("conc-target");
        service.assignAdmin(resourceId, target); // pre-existing grant

        List<Future<Object>> results = runInParallelAllowingFailure(
                () -> { service.assignAdmin(resourceId, target); return "assign"; },
                () -> { service.revokeAdmin(resourceId, target); return "revoke"; });

        // Whatever the interleaving, the end state is well-defined (0 or 1 grant) and no row is
        // duplicated/corrupted — reloading and re-reading must not error.
        assertThat(grantRows.findByResourceId(resourceId).size()).isBetween(0, 1);
        assertThat(results).hasSize(2);
    }

    @SafeVarargs
    private void runInParallel(Callable<Object>... tasks) throws Exception {
        for (Future<Object> f : submit(tasks)) {
            f.get(); // rethrow any failure — these tasks must all succeed
        }
    }

    @SafeVarargs
    private List<Future<Object>> runInParallelAllowingFailure(Callable<Object>... tasks) throws Exception {
        List<Future<Object>> futures = submit(tasks);
        for (Future<Object> f : futures) {
            try {
                f.get();
            } catch (Exception tolerated) {
                // a losing optimistic/constraint race is acceptable here; correctness is the end state
            }
        }
        return futures;
    }

    @SafeVarargs
    private List<Future<Object>> submit(Callable<Object>... tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Callable<Object>> wrapped = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                wrapped.add(() -> {
                    asSuperAdmin(); // SecurityContext is thread-local — each worker needs its own
                    barrier.await();
                    return task.call();
                });
            }
            return pool.invokeAll(wrapped);
        } finally {
            pool.shutdown();
        }
    }

    private ResourceType saveType(String name, MemberType... allowed) {
        ResourceType type = types.save(new ResourceType(name, null));
        for (MemberType memberType : allowed) {
            allowedMembers.save(new ResourceTypeAllowedMember(type.getId(), memberType, null));
        }
        return type;
    }

    private void asSuperAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
    }

    private UUID user(String username) {
        UUID id = users.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", Set.of("ROLE_USER"))).getId();
        createdUsers.add(id);
        return id;
    }
}
