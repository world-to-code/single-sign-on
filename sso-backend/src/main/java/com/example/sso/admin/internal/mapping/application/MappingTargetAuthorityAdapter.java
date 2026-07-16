package com.example.sso.admin.internal.mapping.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.mapping.MappingTargetAuthority;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.user.account.UserService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of the mapping module's {@link MappingTargetAuthority} port: re-runs the grant-authority
 * policy ({@link AdminAccessPolicy#mayAssignTarget}) for a rule's author BY ID, off the request thread. It
 * supplies the one {@code SecurityContext}-bound input the policy needs — the actor's authority set — from
 * {@link UserService#effectiveAuthorities(UUID)} (the same assembly login uses), so the async re-check and the
 * synchronous create/update gate evaluate identically. Keeping this in admin (which already depends on mapping)
 * avoids a mapping→admin module cycle.
 */
@Component
@RequiredArgsConstructor
class MappingTargetAuthorityAdapter implements MappingTargetAuthority {

    private final AdminAccessPolicy accessPolicy;
    private final UserService userService;

    @Override
    public boolean authorMayAssign(UUID authorId, MappingTargetKind kind, UUID targetId) {
        Set<String> authorities = userService.effectiveAuthorities(authorId);
        return accessPolicy.mayAssignTarget(authorId, authorities, kind, targetId);
    }
}
