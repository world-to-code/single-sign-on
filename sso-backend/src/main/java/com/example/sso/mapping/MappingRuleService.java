package com.example.sso.mapping;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages metadata-driven mapping rules (predicate → group membership) in the acting tier. Creating or editing a
 * rule re-evaluates it across the org; deleting it retracts the memberships it materialized. The authorization
 * that the actor may grant the target group's membership is enforced by the admin controller before these are
 * called; the implementation additionally validates the group exists in the acting tier and is not a system group.
 */
public interface MappingRuleService {

    /** Creates a rule and materializes it (adds every currently-matching user to the group). */
    MappingRuleView create(MappingRuleSpec spec);

    /** Updates a rule's predicate/target and re-materializes (adds newly-matching, retracts no-longer-matching). */
    MappingRuleView update(UUID id, MappingRuleSpec spec);

    /** Deletes a rule, retracting every membership it materialized. */
    void delete(UUID id);

    /** Every rule in the acting tier. */
    List<MappingRuleView> list();

    MappingRuleView get(UUID id);

    /** Dry run: the ids of the users the given predicate currently matches in the acting tier. */
    Set<UUID> preview(MappingRuleSpec spec);

    /**
     * For each of these attribute keys, the PRIVILEGE-granting targets that rules reading it confer.
     *
     * <p>Whoever writes such a key chooses who satisfies those rules, so this is the reach a caller has to
     * bound before letting them write it. {@code RESOURCE_MEMBER} is excluded for the same reason the
     * evaluator excludes it: membership of a resource confers no authority.
     *
     * <p>Keys nothing reads are absent from the result rather than mapped to an empty set.
     */
    Map<String, Set<MappingTarget>> privilegeTargetsByKey(Collection<String> attrKeys);

    /**
     * Re-evaluates every rule in the ACTING TIER for one user, NOW, inside the caller's transaction.
     *
     * <p>The ordinary path is asynchronous and should stay that way: an attribute edit fans out to a cohort,
     * and a cohort has no business inside a request. This is for the caller that deleted attributes for ONE
     * person and must not return until the consequences are real — the profile move, whose whole point is that
     * the keys it removes can be conditions on mapping rules.
     *
     * <p>Why it matters that it is synchronous: that caller also terminates the person's sessions on commit.
     * Asynchronously, the sessions were gone while {@code app_user_role} still carried the role, so a re-login
     * in between was fully privileged. Running here means the role is already retracted when the termination
     * fires, and — because the last-administrator invariant runs inside the re-evaluation — a move that would
     * leave the tier with no administrator now fails the move instead of bricking the tenant later.
     *
     * <p>Bounded on purpose, and bounded by construction rather than by argument: it reads only the rules
     * that already CLAIM this user, and it has no materialize branch at all — so no cohort, no per-rule row
     * lock, and the tier lock behind the last-administrator invariant only when an admin-bearing role goes.
     * It iterates to a fixed point, because retracting a GROUP takes back the attributes that group lent the
     * user and another claimed rule may have been matching on one of them.
     */
    void retractStaleClaims(UUID userId);
}
