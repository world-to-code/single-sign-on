package com.example.sso.user.deny;

import java.util.UUID;

/**
 * One stored deny, reduced to what deciding a LIFT needs: the withheld pattern and the author provenance the
 * lift guard compares against.
 *
 * <p>Exists so the authority can be asked about a whole subject's denies in ONE call. Asked row by row, each
 * question re-resolved the acting administrator and re-hydrated their entire effective authority set — roles,
 * group-delegated roles, the inheritance DAG and their own denies — so a subject carrying three denies paid
 * for three full RBAC resolutions, once per mapping target, twice per profile move.
 *
 * <p>Deliberately not {@link DenyRow}: that one is what a console lists and lifts by id, and it carries no
 * provenance on purpose.
 */
public record DenyLift(String pattern, UUID createdBy, UUID writerApexRoleId) {
}
