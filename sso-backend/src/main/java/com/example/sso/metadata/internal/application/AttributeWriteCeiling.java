package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * May this actor write or remove this attribute key. Four questions, in a deliberate order, and none of them
 * touches a stored value — which is why they are here rather than beside the reads and writes they gate.
 *
 * <ul>
 *   <li><b>Ownership.</b> An administrator may not edit a key a DIRECTORY owns: the next sync overwrites the
 *       edit, and a change that silently disappears hours later is worse than one refused now. A GROUP tag is
 *       held to the USER schema too, because a group tag is unioned into every member's attributes and tested
 *       by the same predicate — tagging a group with a directory-owned USER key would forge it for all of them.</li>
 *   <li><b>The grant ceiling.</b> A mapping rule confers a role on whoever carries a value, so writing the key
 *       it reads is a grant by another route.</li>
 *   <li><b>The policy ceiling.</b> A binding decides a live session's posture by testing the key, re-read every
 *       request, so writing OR removing it moves the target's posture.</li>
 *   <li><b>The lift ceiling.</b> The one case where removal does NOT de-escalate: a deny riding on the group or
 *       role a rule confers. Losing the membership loses the deny, handing the withheld permission back.</li>
 * </ul>
 *
 * <p>The polarities differ and the difference is load-bearing. A grant is on the PRESENCE of a value (mapping
 * operators are positive-only), so removal can only retract a grant and carries no grant ceiling. A policy
 * binding tightens on presence, so removal loosens and keeps it. And {@code add} is purely additive: it can
 * retract nothing, so it can lift no deny, and asking it for the lift authority refused writes that bought
 * nothing.
 */
@Component
@RequiredArgsConstructor
class AttributeWriteCeiling {

    private final AttributeDefinitionService definitions;

    /**
     * Lazily, because the admin module implements this guard and admin depends on metadata — a direct
     * injection closes the cycle at startup.
     *
     * <p>{@code getObject()} and NOT {@code ifAvailable()}: an absent guard must be a hard failure, not a
     * silent pass. This ceiling exists to refuse, so "the guard is missing" and "the guard said yes" cannot be
     * allowed to look the same.
     */
    private final ObjectProvider<AttributeValueGrantGuard> grantGuard;

    private final AttributeKeyPolicyGuard policyGuard;

    /** An ADDITIVE write: it inserts a missing (key, value) and takes nothing away. */
    void requireWritable(EntityKind kind, String key) {
        requireLocallyOwned(kind, key);
        requireMayDecideGrants(kind, Set.of(key));
        requireMayDecidePolicy(kind, Set.of(key));
    }

    /** A REPLACING write. {@code set} narrows the key to one value, so it deletes as well as writes — a rule
     *  reading {@code key=x} is defeated by writing {@code key=y} just as surely as by removing the key. */
    void requireReplaceable(EntityKind kind, String key) {
        requireWritable(kind, key);
        refuseFirst(removalRefusals(kind, List.of(key)));
    }

    /** A removal, of one key or of a whole set: refuse the WHOLE set before any of it is deleted. */
    void requireRemovable(EntityKind kind, Collection<String> keys) {
        refuseFirst(removalRefusals(kind, keys));
    }

    /** The same decision, reported instead of thrown — what the console previews before it asks. */
    Set<String> keysNotRemovable(EntityKind kind, Collection<String> keys) {
        return removalRefusals(kind, keys).keySet();
    }

    /**
     * The mirror image, and the half that is easy to forget: a SYNC may only write what its schema says it
     * owns. Without this a mis-mapped connector silently eats values an administrator owns, and an undeclared
     * key would let a sync invent schema by writing to it.
     */
    void requireDirectoryOwned(EntityKind kind, String key) {
        AttributeDefinition definition = definitions.definitionOf(kind, key)
                .orElseThrow(() -> ConflictException.of("attribute.notDeclared", key));
        if (definition.locallyEditable()) {
            throw ConflictException.of("attribute.locallyOwned", key);
        }
    }

    /** The whole-key-set ownership check a bulk write needs before it writes any of them. */
    void requireAllLocallyOwned(EntityKind kind, Collection<String> keys) {
        keys.forEach(key -> requireLocallyOwned(kind, key));
    }

    /**
     * A mapping rule can confer a role on whoever carries a value, so writing the key it reads is a grant by
     * another route. Asked over the WHOLE key set in one call — the guard reaches the mapping rules, so a
     * per-key call re-runs that lookup for every attribute of a bulk write.
     *
     * <p>USER and GROUP only: a group tag is unioned into every member's attributes and tested by the same
     * predicate, so it reaches rules exactly as a user attribute does. Application and resource tags do not.
     */
    void requireMayDecideGrants(EntityKind kind, Collection<String> keys) {
        if (!reachesRulesAndBindings(kind)) {
            return;
        }
        Set<String> beyond = grantGuard.getObject().keysBeyondAuthority(keys);
        if (!beyond.isEmpty()) {
            throw ForbiddenException.of("metadata.attribute.grantGoverned", beyond.iterator().next());
        }
    }

    /** Whoever moves a binding's posture must hold the authority to set that policy themselves. */
    void requireMayDecidePolicy(EntityKind kind, Collection<String> keys) {
        if (!reachesRulesAndBindings(kind)) {
            return;
        }
        Set<String> beyond = policyGuard.keysBeyondAuthority(keys);
        if (!beyond.isEmpty()) {
            throw ForbiddenException.of("metadata.attribute.policyGoverned", beyond.iterator().next());
        }
    }

    /**
     * The single removal decision, in the order the refusals are reported: ownership first (it is nobody's to
     * remove), then the two authority ceilings.
     *
     * <p>Both consumers read THIS. Previously the preview looped the same three questions itself, with a
     * comment promising it matched the write — which is precisely how this codebase has more than once ended
     * up with a check on one path and not the other.
     */
    private Map<String, RemovalRefusal> removalRefusals(EntityKind kind, Collection<String> keys) {
        Map<String, RemovalRefusal> refused = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return refused;
        }
        List<String> distinct = keys.stream().distinct().toList();
        for (String key : distinct) {
            if (isSourceOwned(kind, key) || (kind == EntityKind.GROUP && isSourceOwned(EntityKind.USER, key))) {
                refused.put(key, RemovalRefusal.DIRECTORY_OWNED);
            }
        }
        if (reachesRulesAndBindings(kind)) {
            policyGuard.keysBeyondAuthority(distinct)
                    .forEach(key -> refused.putIfAbsent(key, RemovalRefusal.POLICY_GOVERNED));
            grantGuard.getObject().keysWhoseRemovalLiftsDeny(distinct)
                    .forEach(key -> refused.putIfAbsent(key, RemovalRefusal.DENY_GOVERNED));
        }
        return refused;
    }

    private void refuseFirst(Map<String, RemovalRefusal> refusals) {
        refusals.entrySet().stream().findFirst()
                .ifPresent(refusal -> {
                    throw refusal.getValue().on(refusal.getKey());
                });
    }

    private void requireLocallyOwned(EntityKind kind, String key) {
        refuseIfSourceOwned(kind, key);
        if (kind == EntityKind.GROUP) {
            refuseIfSourceOwned(EntityKind.USER, key);
        }
    }

    private void refuseIfSourceOwned(EntityKind kind, String key) {
        if (isSourceOwned(kind, key)) {
            throw RemovalRefusal.DIRECTORY_OWNED.on(key);
        }
    }

    private boolean isSourceOwned(EntityKind kind, String key) {
        return definitions.definitionOf(kind, key)
                .filter(definition -> !definition.locallyEditable())
                .isPresent();
    }

    /** A group tag is unioned into every member's attributes, so both kinds reach rules and bindings. */
    private boolean reachesRulesAndBindings(EntityKind kind) {
        return kind == EntityKind.USER || kind == EntityKind.GROUP;
    }
}
