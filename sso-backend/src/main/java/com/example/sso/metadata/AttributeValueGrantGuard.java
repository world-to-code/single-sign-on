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
 * <p>Removal is asked SEPARATELY, and deliberately. Most removal de-escalates — taking away the value that
 * granted a role retracts it — and an administrator must always be able to do that even when they could not
 * have granted the role, or the guard becomes a lock on remediation. Only a negative operator
 * ({@code NOT_EXISTS}, {@code NOT_EQUALS}) turns a removal into a grant, so only those bound it.
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
     * The same, for REMOVING these keys: only the rules that confer on their ABSENCE bound it. Empty means the
     * removal may proceed.
     */
    Set<String> keysBeyondAuthorityToRemove(Collection<String> attrKeys);
}
