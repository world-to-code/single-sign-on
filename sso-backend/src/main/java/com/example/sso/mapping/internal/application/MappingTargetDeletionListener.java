package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.internal.domain.MappingRuleRepository;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.GroupDeletedEvent;
import com.example.sso.user.role.RoleDeletedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops the mapping rules targeting a group or role when that target is deleted — the target's own assignments
 * (its group memberships / role grants) are already gone with it, so this only removes the now-dangling rules
 * (their provenance rows cascade via the FK). Runs AFTER the deletion commits in its own transaction
 * ({@code REQUIRES_NEW}); a rule only ever targets a same-tier id, so a platform-scoped sweep by the unique
 * target id cleans up exactly those rules. Mirrors {@code ResourceMemberCleanupListener} on {@code UserDeletedEvent}.
 */
@Component
@RequiredArgsConstructor
public class MappingTargetDeletionListener {

    private final MappingRuleRepository rules;
    private final OrgContext orgContext;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGroupDeleted(GroupDeletedEvent event) {
        dropRulesTargeting(event.groupId());
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRoleDeleted(RoleDeletedEvent event) {
        dropRulesTargeting(event.roleId());
    }

    private void dropRulesTargeting(UUID targetId) {
        orgContext.runAsPlatform(() -> rules.deleteAll(rules.findByTargetId(targetId)));
    }
}
