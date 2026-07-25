package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyAuthor;
import com.example.sso.user.deny.DenyAuthority;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.OrgPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDeny;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.UserPermissionDenyRepository;
import com.example.sso.user.rbac.Permissions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authors and lifts denies. Authorization is delegated to the admin-implemented {@link DenyAuthority} port
 * (grant-symmetric, subject-scoped, dominance-gated, LIVE); this service validates the pattern, derives the
 * deny's tier {@code org_id}, and writes race-proof (a duplicate is a no-op). A refused author/lift is a 403.
 */
@Service
@RequiredArgsConstructor
class DenyServiceImpl implements DenyService {

    private final DenyAuthority denyAuthority;
    private final UserPermissionDenyRepository userDenies;
    private final PrincipalPermissionDenyRepository principalDenies;
    private final OrgPermissionDenyRepository orgDenies;
    private final UserService users;
    private final OrgContext orgContext;

    @Override
    @Transactional
    public UUID create(DenySpec spec) {
        // Validate the pattern independently of authorization: a super passes the grant ceiling by role, so
        // without this an invalid pattern (which subtracts nothing) could be stored and silently do nothing.
        if (!Permissions.isGrantableName(spec.pattern())) {
            throw BadRequestException.of("user.permission.unknown", spec.pattern());
        }
        DenyAuthor author = denyAuthority.authorizeAuthor(spec.kind(), spec.subjectId(), spec.pattern())
                .orElseThrow(() -> ForbiddenException.of("user.deny.notPermitted"));

        UUID orgId = denyOrgId(spec.kind(), spec.subjectId());
        return switch (spec.kind()) {
            case USER -> {
                userDenies.insertIfAbsent(spec.subjectId(), orgId, spec.pattern(), author.id(), author.apexRoleId());
                yield userDenies.findId(spec.subjectId(), spec.pattern()).orElseThrow();
            }
            case ROLE -> createPrincipal(DenySubjectType.ROLE, spec.subjectId(), orgId, spec.pattern(), author);
            case GROUP -> createPrincipal(DenySubjectType.GROUP, spec.subjectId(), orgId, spec.pattern(), author);
            case ORG -> {
                orgDenies.insertIfAbsent(orgId, spec.pattern(), author.id(), author.apexRoleId());
                yield orgDenies.findId(orgId, spec.pattern()).orElseThrow();
            }
        };
    }

    @Override
    @Transactional
    public void lift(UUID denyId, DenySubjectKind kind) {
        switch (kind) {
            case USER -> userDenies.findById(denyId).ifPresent(d ->
                    liftIfPermitted(DenySubjectKind.USER, d.getUserId(), d.getPattern(), d.getCreatedBy(),
                            d.getWriterApexRoleId(), () -> userDenies.deleteById(denyId)));
            case ROLE, GROUP -> principalDenies.findById(denyId).ifPresent(d ->
                    liftIfPermitted(kindOf(d), d.getSubjectId(), d.getPattern(), d.getCreatedBy(),
                            d.getWriterApexRoleId(), () -> principalDenies.deleteById(denyId)));
            case ORG -> orgDenies.findById(denyId).ifPresent(d ->
                    liftIfPermitted(DenySubjectKind.ORG, d.getOrgId(), d.getPattern(), d.getCreatedBy(),
                            d.getWriterApexRoleId(), () -> orgDenies.deleteById(denyId)));
        }
    }

    private UUID createPrincipal(DenySubjectType type, UUID subjectId, UUID orgId, String pattern, DenyAuthor author) {
        principalDenies.insertIfAbsent(type.name(), subjectId, orgId, pattern, author.id(), author.apexRoleId());
        return principalDenies.findId(type, subjectId, pattern).orElseThrow();
    }

    private void liftIfPermitted(DenySubjectKind kind, UUID subjectId, String pattern, UUID createdBy,
            UUID writerApexRoleId, Runnable delete) {
        if (!denyAuthority.mayLift(kind, subjectId, pattern, createdBy, writerApexRoleId)) {
            throw ForbiddenException.of("user.deny.notPermitted");
        }
        delete.run();
    }

    /** The tier the deny row is stamped with: the target user's own org (composite-FK checked); the actor's
     *  acting org for a role/group deny; the target org itself for an org deny. */
    private UUID denyOrgId(DenySubjectKind kind, UUID subjectId) {
        return switch (kind) {
            case USER -> users.findById(subjectId).map(UserAccount::getOrgId)
                    .orElseThrow(() -> BadRequestException.of("user.notFound"));
            case ROLE, GROUP -> orgContext.currentOrg().orElse(null);
            case ORG -> subjectId; // the org being denied is itself the org_id (null = platform veto, super only)
        };
    }

    private DenySubjectKind kindOf(PrincipalPermissionDeny deny) {
        return deny.getSubjectType() == DenySubjectType.ROLE ? DenySubjectKind.ROLE : DenySubjectKind.GROUP;
    }
}
