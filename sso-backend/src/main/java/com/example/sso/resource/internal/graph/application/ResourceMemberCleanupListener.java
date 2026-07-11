package com.example.sso.resource.internal.graph.application;

import com.example.sso.portal.ApplicationDeletedEvent;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.user.GroupDeletedEvent;
import com.example.sso.user.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Purges dangling {@code resource_member} rows when a member target (user/group/application) is deleted
 * elsewhere — the polymorphic member_id has no FK to cascade. Runs in its own transaction after the
 * deleting one commits, so a cleanup miss never blocks (or rolls back) the delete.
 */
@Component
@RequiredArgsConstructor
public class ResourceMemberCleanupListener {

    private final ResourceRepository resources;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserDeleted(UserDeletedEvent event) {
        resources.deleteMembersByTypeAndId(MemberType.USER.name(), event.userId().toString());
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGroupDeleted(GroupDeletedEvent event) {
        resources.deleteMembersByTypeAndId(MemberType.GROUP.name(), event.groupId().toString());
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApplicationDeleted(ApplicationDeletedEvent event) {
        resources.deleteMembersByTypeAndId(MemberType.APPLICATION.name(), event.appId());
    }
}
