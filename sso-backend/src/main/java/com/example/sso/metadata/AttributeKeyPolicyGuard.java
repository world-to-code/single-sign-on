package com.example.sso.metadata;

import java.util.Collection;
import java.util.Set;

/**
 * Which attribute keys the acting administrator must not take control of, because a policy binding decides
 * someone's authentication or session policy by testing them.
 *
 * <p>Aiming a source at an attribute key — or redefining who owns one — hands whoever can then write that key
 * the power to move a live session's posture, since the binding is re-read on every request. This asks the
 * question at ADMISSION, where refusing means the write simply does not happen.
 *
 * <p>The direction is the whole point. Asking it at resolution time instead would mean refusing by declining
 * to match the binding, and a binding that does not match falls back to the organization's default policy,
 * which may be LOOSER — so the guard would become a way to downgrade everyone it governs. A guard whose
 * failure mode is "everybody gets the weaker policy" protects nobody.
 *
 * <p>Declared here and implemented in {@code portal}, which owns the bindings: portal already depends on
 * metadata, so the reverse edge would be a cycle. Mirrors {@code SessionBindings} and {@code AppPolicyBindings}.
 */
public interface AttributeKeyPolicyGuard {

    /**
     * The subset of {@code attrKeys} that some policy binding tests and whose policies the acting
     * administrator could not have set themselves. Empty means the write may proceed.
     *
     * <p>Fails CLOSED: a condition whose binding cannot be read vouches for nothing, and so does an
     * unauthenticated caller.
     */
    Set<String> keysBeyondAuthority(Collection<String> attrKeys);
}
