package com.example.sso.admin.internal.shared.application;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.user.account.UserService;
import com.example.sso.user.deny.DenyAuthor;
import com.example.sso.user.deny.DenyLift;
import com.example.sso.user.deny.DenySubjectKind;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.RoleHierarchyService;
import com.example.sso.user.role.Roles;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may AUTHOR a deny, and who may LIFT one.
 *
 * <p>A deny subtracts a permission, so it is grant-symmetric: authoring one needs the authority to GRANT the
 * pattern, and lifting one restores authority and is therefore itself a grant act. Both also need reach over
 * the subject — the same reach the grant path demands, per subject kind.
 *
 * <p>Two things separate it from the ordinary grant ceiling, and both are deliberate:
 *
 * <ul>
 *   <li><b>LIVE authority, not the session's.</b> {@code mayGrantPermissions} reads the frozen authorities of
 *       the request; this re-reads the actor's effective set, so an administrator who has just lowered their
 *       own grant power cannot still author against the old one.</li>
 *   <li><b>Dominance over the AUTHOR, not just the pattern.</b> Lifting needs to have authored the deny or to
 *       STRICTLY dominate the author's stamped apex — a peer cannot undo a peer's deny. Without that, the deny
 *       model would be advisory between equals.</li>
 * </ul>
 *
 * <p>And one refusal that is neither: nobody lifts a deny on their OWN account, whatever their rank.
 */
@Component
@RequiredArgsConstructor
class DenyAuthorityPolicy {

    private final ActingAdmin actingAdmin;
    private final AdminScope scope;
    private final RoleGrantCeiling ceiling;
    private final UserService userService;
    private final RoleHierarchyService roleHierarchy;

    /** The author's id plus their single apex role, for stamping — refused if their position is ambiguous. */
    Optional<DenyAuthor> authorizeDenyAuthor(DenySubjectKind kind, UUID subjectId, String pattern) {
        return actingAdmin.id().flatMap(actorId -> {
            if (!mayGrantLive(userService.effectiveAuthorities(actorId), pattern)
                    || !mayReachDenySubject(kind, subjectId)) {
                return Optional.empty();
            }
            Set<UUID> apex = roleHierarchy.apexRolesOf(actorId);
            return apex.size() == 1 ? Optional.of(new DenyAuthor(actorId, apex.iterator().next())) : Optional.empty();
        });
    }

    boolean mayLiftDeny(DenySubjectKind kind, UUID subjectId, String pattern, UUID createdBy,
            UUID writerApexRoleId) {
        return mayLiftDenies(kind, subjectId, List.of(new DenyLift(pattern, createdBy, writerApexRoleId)));
    }

    /**
     * The same decision for a whole subject's denies, with the actor resolved ONCE — identical verdict to
     * asking per row, because the actor-derived inputs (their id, their effective authorities, whether they
     * reach the subject) do not vary across rows. Per row they were re-derived every time, and
     * {@code effectiveAuthorities} re-hydrates roles, group-delegated roles, the inheritance DAG and the
     * actor's own denies.
     *
     * <p>Empty batch is true: nothing to lift, nothing to authorize. Unresolved actor is false, as ever.
     */
    boolean mayLiftDenies(DenySubjectKind kind, UUID subjectId, Collection<DenyLift> denies) {
        return mayLiftDeniesOn(kind, Map.of(subjectId, List.copyOf(denies)));
    }

    /**
     * The same decision over SEVERAL subjects, which is the shape every caller actually has — a role replace,
     * a delegation change, a group and the roles it delegates. The actor and their effective authority set are
     * resolved ONCE for the whole set; only reach and the per-row provenance vary by subject.
     *
     * <p>Subjects carrying no denies are dropped before anything is resolved, preserving the single-subject
     * rule that an empty set authorizes nothing and therefore needs no actor: it is not a lift.
     */
    boolean mayLiftDeniesOn(DenySubjectKind kind, Map<UUID, List<DenyLift>> deniesBySubject) {
        Map<UUID, List<DenyLift>> lifting = deniesBySubject.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (lifting.isEmpty()) {
            return true;
        }
        return actingAdmin.id().map(actorId -> {
            Set<String> actorAuthorities = userService.effectiveAuthorities(actorId);
            return lifting.entrySet().stream()
                    .allMatch(entry -> mayLift(kind, entry.getKey(), entry.getValue(), actorId, actorAuthorities));
        }).orElse(false);
    }

    private boolean mayLift(DenySubjectKind kind, UUID subjectId, List<DenyLift> denies, UUID actorId,
            Set<String> actorAuthorities) {
        if (kind == DenySubjectKind.USER && actorId.equals(subjectId)) {
            return false; // never re-grant yourself by lifting a deny on your own account
        }
        if (!mayReachDenySubject(kind, subjectId)) {
            return false;
        }
        return denies.stream().allMatch(deny -> mayGrantLive(actorAuthorities, deny.pattern())
                && (actorId.equals(deny.createdBy()) || strictlyDominates(actorId, deny.writerApexRoleId())));
    }

    /** LIVE grant-only-what-you-hold for ONE name: a super grants anything; else a grantable, non-platform name
     *  the actor's live authorities actually hold (the wildcard token or the concrete permission). */
    private boolean mayGrantLive(Set<String> authorities, String pattern) {
        return authorities.contains(Roles.ADMIN)
                || (Permissions.isGrantableName(pattern) && !Permissions.isPlatformGrant(pattern)
                        && authorities.contains(pattern));
    }

    /** The actor administers the deny's subject — the SAME access the grant path demands, per subject kind. */
    private boolean mayReachDenySubject(DenySubjectKind kind, UUID subjectId) {
        return switch (kind) {
            case USER -> scope.canAccessUser(subjectId);
            case ROLE -> ceiling.mayAssignTarget(MappingTargetKind.ROLE, subjectId);
            case GROUP -> scope.canAccessGroup(subjectId);
            // A platform super may author for any org (or the org-null platform veto); a tenant only its own.
            case ORG -> scope.isCurrentActorUnscoped()
                    || (subjectId != null && scope.administersBoundOrg() && subjectId.equals(scope.actingOrg()));
        };
    }

    /** The actor is strictly above the author's frozen apex: may manage that role AND it is not at their level. */
    private boolean strictlyDominates(UUID actorId, UUID writerApexRoleId) {
        return writerApexRoleId != null
                && roleHierarchy.actorMayManageRole(actorId, writerApexRoleId)
                && !roleHierarchy.apexRolesOf(actorId).contains(writerApexRoleId);
    }
}
