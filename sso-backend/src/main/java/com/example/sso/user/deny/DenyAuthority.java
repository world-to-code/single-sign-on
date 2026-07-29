package com.example.sso.user.deny;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The authorization policy for authoring and lifting denies — declared in the {@code user} module (which owns
 * the deny store) and IMPLEMENTED in {@code admin} (which holds the grant-authority policy), so the store never
 * depends on the admin module (a cycle). Mirrors {@code mapping}'s {@code MappingTargetAuthority}.
 *
 * <p>Both checks resolve the actor's authority LIVE (never a frozen session), so a deny that lowered the actor's
 * own grant power takes effect against them at once. Both fail CLOSED.
 */
public interface DenyAuthority {

    /**
     * Whether the current actor may AUTHOR a deny of {@code pattern} on the subject, and if so who the author is
     * (id + single apex role, to stamp onto the row). Empty = refused. Deny is grant-symmetric: the actor may
     * deny a permission only if they could GRANT it, only on a subject they administer, and only from a single
     * unambiguous role position — an actor with more than one apex role is refused (fail-closed).
     */
    Optional<DenyAuthor> authorizeAuthor(DenySubjectKind kind, UUID subjectId, String pattern);

    /**
     * Whether the current actor may LIFT the given stored deny: they authored it ({@code createdBy}), or they
     * STRICTLY dominate the author's stamped apex ({@code writerApexRoleId}) — a peer cannot lift a peer's deny —
     * plus the grant-symmetric ceiling on {@code pattern}, access to the subject, a matching tier, and the deny
     * not being on the actor themselves.
     */
    boolean mayLift(DenySubjectKind kind, UUID subjectId, String pattern, UUID createdBy, UUID writerApexRoleId);

    /**
     * Whether the actor may lift EVERY one of these denies on the subject — the batch form of
     * {@link #mayLift}, and the one to use when a caller holds a whole subject's rows.
     *
     * <p>Same verdict, one authority resolution: the per-row form re-reads the acting administrator and
     * re-hydrates their full effective authority set for each row. True for an empty batch — there is nothing
     * to lift, so nothing to authorize. Fails CLOSED like its sibling.
     */
    boolean mayLiftAll(DenySubjectKind kind, UUID subjectId, Collection<DenyLift> denies);

    /**
     * The same verdict over SEVERAL subjects of one kind, with the acting administrator resolved once.
     *
     * <p>Asked per subject, every call re-derived the actor and re-hydrated their entire effective authority
     * set — roles, group-delegated roles, the inheritance DAG and their own denies. A user losing eight roles,
     * or a group delegating eight, paid that eight times for one decision.
     *
     * <p>All or nothing: one subject the actor may not lift refuses the set, because every caller is asking
     * about a change it will either make whole or refuse whole.
     */
    boolean mayLiftAll(DenySubjectKind kind, Map<UUID, List<DenyLift>> deniesBySubject);
}
