package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceRoleTier;
import com.example.sso.user.Roles;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Postgres-graph {@link ResourceScope}: {@code managedResourceIds} runs a recursive CTE over
 * {@code resource_edge} seeded by the actor's ADMIN grants. The managed set is memoized per HTTP
 * request as an IMMUTABLE copy (an authorization decision may consult it several times while
 * listing; sharing a mutable set across authz decisions would invite poisoning). Outside a request
 * (tests, background work) it is computed directly. The memo is intentionally request-scoped-stale:
 * a grant changed mid-request becomes visible on the next request.
 *
 * <p><b>SECURITY INVARIANT:</b> never SHRINK an actor's scope then RE-CHECK it in the same request — the
 * memo would serve the pre-shrink (larger) set and fail OPEN. Thin controllers hold this (one mutation
 * per request); if that changes, evict the actor's memo after the write. Growing scope is stale-safe.
 */
@Component
@RequiredArgsConstructor
public class ResourceScopeImpl implements ResourceScope {

    private static final String CACHE_KEY = ResourceScopeImpl.class.getName() + ".managed";

    private final ResourceRepository resources;
    private final UserService users;
    private final UserGroupService userGroups;

    @Override
    public Set<UUID> managedResourceIds(UUID actorUserId) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return loadManagedIds(actorUserId);
        }

        String key = CACHE_KEY + ":" + actorUserId;
        @SuppressWarnings("unchecked")
        Set<UUID> cached = (Set<UUID>) request.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
        if (cached == null) {
            cached = loadManagedIds(actorUserId);
            request.setAttribute(key, cached, RequestAttributes.SCOPE_REQUEST);
        }
        return cached;
    }

    /**
     * Unscoped = an EFFECTIVE super admin, matching the session's authority model
     * ({@code SsoUserDetailsService}): a direct {@code ROLE_ADMIN} or one delegated via a group.
     * Diverging from the effective model would silently scope out group-delegated admins.
     */
    @Override
    public boolean isUnscoped(UUID actorUserId) {
        return users.hasRole(actorUserId, Roles.ADMIN)
                || userGroups.membershipsForUser(actorUserId).stream()
                        .flatMap(membership -> membership.roles().stream())
                        .anyMatch(role -> Roles.ADMIN.equals(role.getName()));
    }

    @Override
    public boolean reaches(UUID ancestorId, UUID descendantId) {
        return resources.reaches(ancestorId, descendantId);
    }

    private Set<UUID> loadManagedIds(UUID actorUserId) {
        return Set.copyOf(resources.findManagedResourceIds(actorUserId, ResourceRoleTier.ADMIN.name()));
    }
}
