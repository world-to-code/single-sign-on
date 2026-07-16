package com.example.sso.user.internal.application;

import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.group.GroupMembershipChangedEvent;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        terminate(users.findAllById(Set.copyOf(userIds)));
    }

    /**
     * A group-membership change: terminate the affected users' sessions AND announce the membership shift so
     * consumers (auto-mapping) can re-evaluate GROUP-inherited state for exactly those users. One membership event
     * for the cohort, keyed by their shared org (a group and its members are same-org).
     */
    void membershipChanged(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        List<AppUser> resolved = users.findAllById(Set.copyOf(userIds));
        terminate(resolved);
        // One membership event keyed by the cohort's org. A group and its members are strict same-org (enforced on
        // write — see same-org-reference-invariants), so the first user's org keys the whole set; a consumer
        // re-evaluates under that one tier.
        resolved.stream().findFirst().ifPresent(any -> events.publishEvent(new GroupMembershipChangedEvent(
                resolved.stream().map(AppUser::getId).collect(Collectors.toSet()), any.getOrgId())));
    }

    /** The single construction site for the session-termination event, shared by both publish paths. */
    private void terminate(List<AppUser> accounts) {
        accounts.forEach(user -> events.publishEvent(new UserAccessChangedEvent(user.getUsername(), user.getOrgId())));
    }
}
