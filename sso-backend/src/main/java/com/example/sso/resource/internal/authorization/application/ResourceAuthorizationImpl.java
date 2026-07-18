package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.authorization.ResourceAuthorization;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Postgres-graph {@link ResourceAuthorization}: delegates the scope questions to {@link ResourceScope}. */
@Service
@RequiredArgsConstructor
public class ResourceAuthorizationImpl implements ResourceAuthorization {

    private final ResourceScope scope;

    @Override
    public boolean isUnscoped(UUID actorUserId) {
        return scope.isUnscoped(actorUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canView(UUID actorUserId, UUID resourceId) {
        return scope.isUnscoped(actorUserId) || scope.viewableResourceIds(actorUserId).contains(resourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canManage(UUID actorUserId, UUID resourceId) {
        return scope.isUnscoped(actorUserId) || scope.managedResourceIds(actorUserId).contains(resourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> managedResourceIds(UUID actorUserId) {
        return scope.managedResourceIds(actorUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> viewableResourceIds(UUID actorUserId) {
        return scope.viewableResourceIds(actorUserId);
    }
}
