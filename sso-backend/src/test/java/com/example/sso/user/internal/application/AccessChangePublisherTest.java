package com.example.sso.user.internal.application;

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.group.GroupMembershipChangedEvent;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A group-membership change must BOTH end the affected users' sessions ({@link UserAccessChangedEvent}) AND
 * announce the shift ({@link GroupMembershipChangedEvent}) so a consumer (auto-mapping) can re-evaluate them.
 * Guards that neither half is dropped, since the integration tests observe only the mapping effect.
 */
class AccessChangePublisherTest {

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AccessChangePublisher publisher = new AccessChangePublisher(events, users);

    @Test
    void membershipChangedEndsEachUsersSessionAndAnnouncesTheMembershipShift() {
        UUID orgId = UUID.randomUUID();
        AppUser alice = user("alice", orgId);
        AppUser bob = user("bob", orgId);
        when(users.findAllById(anySet())).thenReturn(List.of(alice, bob));

        publisher.membershipChanged(Set.of(alice.getId(), bob.getId()));

        verify(events).publishEvent(new UserAccessChangedEvent("alice", orgId)); // session termination, per user
        verify(events).publishEvent(new UserAccessChangedEvent("bob", orgId));
        verify(events).publishEvent(new GroupMembershipChangedEvent(Set.of(alice.getId(), bob.getId()), orgId));
    }

    @Test
    void anEmptyMembershipChangePublishesNothing() {
        publisher.membershipChanged(Set.of());
        verifyNoInteractions(events);
    }

    private AppUser user(String username, UUID orgId) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getUsername()).thenReturn(username);
        when(user.getOrgId()).thenReturn(orgId);
        return user;
    }
}
