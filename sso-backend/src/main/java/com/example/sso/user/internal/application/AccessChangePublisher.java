package com.example.sso.user.internal.application;

import com.example.sso.user.UserAccessChangedEvent;
import com.example.sso.user.internal.domain.AppUserRepository;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link UserAccessChangedEvent} for the users whose effective authorities a role or group
 * mutation changed, so the session module ends their live sessions (a frozen, Redis-serialized
 * SecurityContext must not keep acting on stale roles/permissions). Centralizes the id→username
 * resolution these callers share; user-centric changes publish directly from {@link UserServiceImpl}.
 */
@Component
@RequiredArgsConstructor
class AccessChangePublisher {

    private final ApplicationEventPublisher events;
    private final AppUserRepository users;

    /** Terminates the sessions of the given users (resolved to usernames — the session index key). */
    void forUserIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        users.findAllById(Set.copyOf(userIds))
                .forEach(user -> events.publishEvent(new UserAccessChangedEvent(user.getUsername(), user.getOrgId())));
    }
}
