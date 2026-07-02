package com.example.sso.resource.internal.application;

import com.example.sso.portal.ApplicationDeletedEvent;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.user.GroupDeletedEvent;
import com.example.sso.user.UserDeletedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Unit test proving each deletion event purges the matching polymorphic {@code resource_member} rows —
 * the member_id has no FK to cascade, so the listener IS the cleanup. The unit's job is the interaction
 * (event → repository bulk delete with the correct type/id), so this asserts with {@code verify}.
 */
@ExtendWith(MockitoExtension.class)
class ResourceMemberCleanupListenerTest {

    @Mock
    private ResourceRepository resources;

    @InjectMocks
    private ResourceMemberCleanupListener listener;

    @Test
    void onUserDeletedPurgesUserMembers() {
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(resources).deleteMembersByTypeAndId(MemberType.USER.name(), userId.toString());
        verifyNoMoreInteractions(resources);
    }

    @Test
    void onGroupDeletedPurgesGroupMembers() {
        UUID groupId = UUID.randomUUID();

        listener.onGroupDeleted(new GroupDeletedEvent(groupId));

        verify(resources).deleteMembersByTypeAndId(MemberType.GROUP.name(), groupId.toString());
        verifyNoMoreInteractions(resources);
    }

    @Test
    void onApplicationDeletedPurgesApplicationMembers() {
        listener.onApplicationDeleted(new ApplicationDeletedEvent("shop-client"));

        verify(resources).deleteMembersByTypeAndId(MemberType.APPLICATION.name(), "shop-client");
        verifyNoMoreInteractions(resources);
    }
}
