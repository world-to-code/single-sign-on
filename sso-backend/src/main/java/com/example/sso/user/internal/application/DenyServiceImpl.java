package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyAuthor;
import com.example.sso.user.deny.DenyAuthority;
import com.example.sso.user.deny.DenyRow;
import com.example.sso.user.deny.DenyService;
import com.example.sso.user.deny.DenySpec;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.deny.LastAdminInvariant;
import com.example.sso.user.internal.rbac.domain.DenySubjectType;
import com.example.sso.user.internal.rbac.domain.OrgPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDeny;
import com.example.sso.user.internal.rbac.domain.PrincipalPermissionDenyRepository;
import com.example.sso.user.internal.rbac.domain.UserPermissionDenyRepository;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final DenyAffectedUsers affectedUsers;
    private final AccessChangePublisher accessChanges;
    private final LastAdminInvariant lastAdminInvariant;

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
                boolean created =
                        userDenies.insertIfAbsent(spec.subjectId(), orgId, spec.pattern(), author.id(),
                                author.apexRoleId()) == 1;
                UUID denyId = userDenies.findId(spec.subjectId(), spec.pattern()).orElseThrow();
                afterCreate(created, spec, orgId);
                yield denyId;
            }
            case ROLE -> createPrincipal(DenySubjectType.ROLE, spec, orgId, author);
            case GROUP -> createPrincipal(DenySubjectType.GROUP, spec, orgId, author);
            case ORG -> {
                boolean created =
                        orgDenies.insertIfAbsent(orgId, spec.pattern(), author.id(), author.apexRoleId()) == 1;
                UUID denyId = orgDenies.findId(orgId, spec.pattern()).orElseThrow();
                afterCreate(created, spec, orgId);
                yield denyId;
            }
        };
    }

    @Override
    @Transactional
    public void lift(UUID denyId, DenySubjectKind kind) {
        switch (kind) {
            case USER -> userDenies.findById(denyId).ifPresent(d ->
                    liftIfPermitted(DenySubjectKind.USER, d.getUserId(), d.getOrgId(), d.getPattern(),
                            d.getCreatedBy(), d.getWriterApexRoleId(), () -> userDenies.deleteById(denyId)));
            case ROLE, GROUP -> principalDenies.findById(denyId).ifPresent(d ->
                    liftIfPermitted(kindOf(d), d.getSubjectId(), d.getOrgId(), d.getPattern(),
                            d.getCreatedBy(), d.getWriterApexRoleId(), () -> principalDenies.deleteById(denyId)));
            case ORG -> orgDenies.findById(denyId).ifPresent(d ->
                    liftIfPermitted(DenySubjectKind.ORG, d.getOrgId(), d.getOrgId(), d.getPattern(),
                            d.getCreatedBy(), d.getWriterApexRoleId(), () -> orgDenies.deleteById(denyId)));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DenyRow> userDenies(UUID userId) {
        return userDenies.findByUserId(userId).stream()
                .map(deny -> new DenyRow(deny.getId(), deny.getPattern())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DenyRow> principalDenies(DenySubjectKind kind, UUID subjectId) {
        DenySubjectType type = kind == DenySubjectKind.ROLE ? DenySubjectType.ROLE : DenySubjectType.GROUP;
        return principalDenies.findBySubjectTypeAndSubjectId(type, subjectId).stream()
                .map(deny -> new DenyRow(deny.getId(), deny.getPattern())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DenyRow> orgDenies(UUID orgId) {
        return orgDenies.findByOrgId(orgId).stream()
                .map(deny -> new DenyRow(deny.getId(), deny.getPattern())).toList();
    }

    private UUID createPrincipal(DenySubjectType type, DenySpec spec, UUID orgId, DenyAuthor author) {
        boolean created = principalDenies.insertIfAbsent(type.name(), spec.subjectId(), orgId, spec.pattern(),
                author.id(), author.apexRoleId()) == 1;
        UUID denyId = principalDenies.findId(type, spec.subjectId(), spec.pattern()).orElseThrow();
        afterCreate(created, spec, orgId);
        return denyId;
    }

    /** After a NEW deny row: first refuse it if it stripped a reached tier's last administrator (rolls the write
     *  back), then terminate the affected subjects' sessions. Order matters — a rejected deny must not fire a
     *  session-termination event (it would be discarded on rollback anyway, but the intent is: no brick, no
     *  side effects). An idempotent re-create changed nothing, so it neither guards nor terminates.
     *
     *  <p>The fan-out is computed ONCE and serves both: the users whose sessions must re-resolve are also the
     *  users whose tiers the deny can brick, so the guard is asked about exactly the tiers the deny reaches —
     *  which for a null-org platform veto is every tenant it lands in, not the (deny-exempt) platform tier. */
    private void afterCreate(boolean created, DenySpec spec, UUID orgId) {
        if (!created) {
            return;
        }
        Set<UUID> affected = affectedUsers.forSubject(spec.kind(), spec.subjectId(), orgId);
        lastAdminInvariant.ensureDenyRetainsAdmins(spec.pattern(),
                affectedUsers.tiersFor(spec.kind(), orgId, affected));
        accessChanges.forUserIds(affected);
    }

    private void liftIfPermitted(DenySubjectKind kind, UUID subjectId, UUID orgId, String pattern, UUID createdBy,
            UUID writerApexRoleId, Runnable delete) {
        if (!denyAuthority.mayLift(kind, subjectId, pattern, createdBy, writerApexRoleId)) {
            throw ForbiddenException.of("user.deny.notPermitted");
        }
        delete.run();
        // Lifting a deny widens access; its former subjects' live sessions must re-resolve so their authority
        // comes from the new state rather than the one frozen into a live session. A lift always changed
        // something (the row existed), and widening can never brick a tier — so no guard, always terminate.
        accessChanges.forUserIds(affectedUsers.forSubject(kind, subjectId, orgId));
    }

    /** The tier the deny row is stamped with: the target user's own org (composite-FK checked); the actor's
     *  acting org for a role/group deny; the target org itself for an org deny. */
    private UUID denyOrgId(DenySubjectKind kind, UUID subjectId) {
        return switch (kind) {
            case USER -> users.findById(subjectId).map(UserAccount::getOrgId)
                    .orElseThrow(() -> BadRequestException.of("user.notFound"));
            case ROLE, GROUP -> actingTierOrPlatformVeto();
            case ORG -> subjectId; // the org being denied is itself the org_id (null = platform veto, super only)
        };
    }

    /**
     * The acting tier, or the PLATFORM VETO (null) — which is an asserted capability, never the fall-through
     * value of an unbound context. {@code currentOrg()} is empty for a platform actor AND for a fully
     * authenticated user carrying no org marker; stamping the latter's deny null would apply it in every tenant.
     */
    private UUID actingTierOrPlatformVeto() {
        Optional<UUID> actingOrg = orgContext.currentOrg();
        if (actingOrg.isPresent()) {
            return actingOrg.get();
        }
        if (!orgContext.isPlatform()) {
            throw ForbiddenException.of("user.deny.notPermitted");
        }
        return null;
    }

    private DenySubjectKind kindOf(PrincipalPermissionDeny deny) {
        return deny.getSubjectType() == DenySubjectType.ROLE ? DenySubjectKind.ROLE : DenySubjectKind.GROUP;
    }
}
