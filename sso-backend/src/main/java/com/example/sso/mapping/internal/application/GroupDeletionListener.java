package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.GroupDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the mapping rules targeting a group when the group is deleted — the group's memberships are already
 * gone with it, so this only removes the now-dangling rules (their provenance rows cascade via the FK). Runs
 * AFTER the deletion commits in its own transaction ({@code REQUIRES_NEW}); a rule only ever targets a group in
 * its own tier, so a platform-scoped sweep by group id cleans up exactly those rules. Mirrors
 * {@code ResourceMemberCleanupListener} on {@code UserDeletedEvent}.
 */
@Component
@RequiredArgsConstructor
public class GroupDeletionListener {

    private final MappingRuleRepository rules;
    private final OrgContext orgContext;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGroupDeleted(GroupDeletedEvent event) {
        orgContext.runAsPlatform(() -> rules.deleteAll(rules.findByTargetId(event.groupId())));
    }
}
