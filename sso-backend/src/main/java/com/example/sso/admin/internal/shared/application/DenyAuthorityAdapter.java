package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.deny.DenyAuthor;
import com.example.sso.user.deny.DenyAuthority;
import com.example.sso.user.deny.DenyLift;
import com.example.sso.user.deny.DenySubjectKind;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of the {@code user} module's {@link DenyAuthority} port: authoring and lifting a deny
 * are grant-authority decisions, which live in {@link AdminAccessPolicy}. Keeping the impl here (admin already
 * depends on user) avoids a user→admin cycle, exactly like {@code MappingTargetAuthority}.
 */
@Component
@RequiredArgsConstructor
class DenyAuthorityAdapter implements DenyAuthority {

    private final AdminAccessPolicy accessPolicy;

    @Override
    public Optional<DenyAuthor> authorizeAuthor(DenySubjectKind kind, UUID subjectId, String pattern) {
        return accessPolicy.authorizeDenyAuthor(kind, subjectId, pattern);
    }

    @Override
    public boolean mayLift(DenySubjectKind kind, UUID subjectId, String pattern, UUID createdBy,
            UUID writerApexRoleId) {
        return accessPolicy.mayLiftDeny(kind, subjectId, pattern, createdBy, writerApexRoleId);
    }

    @Override
    public boolean mayLiftAll(DenySubjectKind kind, UUID subjectId, Collection<DenyLift> denies) {
        return accessPolicy.mayLiftDenies(kind, subjectId, denies);
    }
}
