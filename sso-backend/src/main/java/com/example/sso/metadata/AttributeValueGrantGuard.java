package com.example.sso.metadata;

import java.util.Collection;
import java.util.Set;

/**
 * Which attribute keys the acting administrator must not write, because a mapping rule confers a privilege on
 * whoever carries the right value.
 *
 * <p>Group membership already works this way and is already gated: putting someone in a group hands them the
 * group's delegated roles, so the write is refused unless the actor could have conferred those roles by hand.
 * Writing an attribute a mapping rule reads is the same act by a different route — the writer chooses who
 * satisfies the rule — and was not gated at all.
 *
 * <p>The evaluator gates the DIRECTORY half of this and says why: "the person who aimed that directory chooses
 * which users satisfy the rule — without ever needing authority over its target". For a locally-editable key
 * that person is whoever writes the value, and its branch returned true unconditionally.
 *
 * <p>Only WRITES are bounded. Removal de-escalates — taking away the value that granted a role retracts it —
 * and because mapping-rule operators are positive-only ({@code NOT_EXISTS}/{@code NOT_EQUALS} are rejected at
 * rule creation), removal can never make a grant, so an administrator may always retract. Adding a negative
 * operator later would make removal a grant vector and would need a symmetric ceiling here.
 *
 * <p>Declared here and implemented in {@code admin}, which already reaches both the rules and the grant
 * ceiling; metadata depends on neither, and the reverse edge would be a cycle.
 */
public interface AttributeValueGrantGuard {

    /**
     * The subset of {@code attrKeys} whose values decide a grant the acting administrator could not have made
     * by hand. Empty means the write may proceed.
     *
     * <p>Fails CLOSED: an unresolved actor may write none of them.
     */
    Set<String> keysBeyondAuthority(Collection<String> attrKeys);

    /**
     * The subset of {@code attrKeys} the acting administrator must not REMOVE, because dropping the group or
     * role a mapping rule confers on their bearer would also drop a DENY riding on it.
     *
     * <p>This is the exception to "removal only de-escalates". That premise is true of grants — mapping-rule
     * operators are positive-only, so taking a value away can only retract one — but a deny has the opposite
     * polarity: it SUBTRACTS from whoever holds the membership, so losing the membership hands the permission
     * back. Deleting the attribute is then a way to lift a deny without going through the lift authority, and
     * it works on the actor's own account, where lifting is refused outright.
     *
     * <p>Fails CLOSED, like its sibling.
     */
    Set<String> keysWhoseRemovalLiftsDeny(Collection<String> attrKeys);
}
