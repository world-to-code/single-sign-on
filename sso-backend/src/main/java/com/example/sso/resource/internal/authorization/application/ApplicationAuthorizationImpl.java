package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.authorization.ApplicationAuthorization;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postgres-graph {@link ApplicationAuthorization}: an application is in scope when its id is an
 * APPLICATION member of one of the actor's managed resources.
 */
@Service
@RequiredArgsConstructor
public class ApplicationAuthorizationImpl implements ApplicationAuthorization {

    private final ResourceScope scope;
    private final ResourceRepository resources;

    @Override
    @Transactional(readOnly = true)
    public boolean canView(UUID actorUserId, String appId) {
        return canManage(actorUserId, appId); // VIEWER-tier semantics arrive in Phase 2
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canManage(UUID actorUserId, String appId) {
        return scope.isUnscoped(actorUserId) || scopedAppIds(actorUserId).contains(appId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> scopedAppIds(UUID actorUserId) {
        Set<UUID> managed = scope.managedResourceIds(actorUserId);
        if (managed.isEmpty()) {
            return Set.of();
        }
        return resources.findMemberIds(managed, MemberType.APPLICATION.name());
    }
}
