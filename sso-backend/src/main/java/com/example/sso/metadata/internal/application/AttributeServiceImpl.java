package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeKeyPolicyGuard;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityAttributeChangedEvent;
import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.EntityAttribute;
import com.example.sso.metadata.internal.domain.EntityAttributeRepository;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AttributeService}. Every write stamps the acting tenant tier ({@link OrgTierGuard}) and RLS
 * confines both reads and writes, so a tenant only ever touches its own attributes; a platform super (tier
 * null) manages the global ones. On read, a tenant's own attribute shadows a global one of the same key.
 */
@Service
@RequiredArgsConstructor
class AttributeServiceImpl implements AttributeService {

    private final EntityAttributeRepository attributes;
    private final AttributeDefinitionService definitions;
    private final OrgTierGuard tierGuard;
    private final ApplicationEventPublisher events;
    /**
     * Lazy, to break a construction cycle: the guard reaches the mapping rules, whose evaluator reads the
     * attributes this service owns. Resolved on first use, long after construction — the same device
     * {@code OrgContext} uses for its connection binder.
     *
     * <p>{@code getObject()} rather than {@code ifAvailable()}: a missing binder there legitimately means "no
     * transaction", but a missing guard here would mean the ceiling silently stops being enforced. It throws
     * instead, which is loud.
     */
    private final ObjectProvider<AttributeValueGrantGuard> grantGuard;
    /**
     * The policy-binding twin of {@link #grantGuard}. A binding tests an attribute to pick a stricter auth or
     * session policy, re-read on every request, so writing OR removing the value it reads moves a live session's
     * posture — and removal is the sharper edge: dropping the value falls the target back to the looser org
     * default. Whoever moves posture must hold the authority to set that policy. Injected directly, not lazily:
     * the portal impl reads only the bindings, never back into this service, so there is no construction cycle.
     */
    private final AttributeKeyPolicyGuard policyGuard;

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> attributesOf(EntityKind kind, String entityId) {
        UUID tier = tierGuard.currentTier();
        return effectiveOf(attributes.findByEntityKindAndEntityIdOrderByAttrKey(kind, entityId), tier).stream()
                .sorted(Comparator.comparing(Attribute::key).thenComparing(Attribute::value)) // key then value
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> unionAttributesOf(EntityKind kind, Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        UUID tier = tierGuard.currentTier();
        Map<String, List<EntityAttribute>> byEntity = attributes.findByEntityKindAndEntityIdIn(kind, entityIds)
                .stream().collect(Collectors.groupingBy(EntityAttribute::getEntityId));
        // Union across the entities: every effective (key,value) any of them carries, own-shadows-global per entity.
        Set<Attribute> union = new LinkedHashSet<>();
        byEntity.values().forEach(rows -> union.addAll(effectiveOf(rows, tier)));
        return List.copyOf(union);
    }

    /** One entity's EFFECTIVE (key,value) pairs, multi-value: for each key the acting tier's own values SHADOW the
     *  global ones — if the entity carries any own-tier row for a key only its values show, otherwise the global
     *  values do (a per-key value-set shadow, the multi-value generalization of the old single-value shadow). */
    private Set<Attribute> effectiveOf(List<EntityAttribute> rows, UUID tier) {
        Set<String> keysWithOwn = rows.stream()
                .filter(row -> Objects.equals(row.getOrgId(), tier))
                .map(EntityAttribute::getAttrKey)
                .collect(Collectors.toSet());
        Set<Attribute> effective = new LinkedHashSet<>();
        for (EntityAttribute row : rows) {
            boolean own = Objects.equals(row.getOrgId(), tier);
            boolean inheritedGlobal = row.getOrgId() == null && !own && !keysWithOwn.contains(row.getAttrKey());
            if (own || inheritedGlobal) {
                effective.add(new Attribute(row.getAttrKey(), row.getAttrValue()));
            }
        }
        return effective;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> unionAttributesOfInTier(EntityKind kind, Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        UUID tier = tierGuard.currentTier();
        // Scoped in the QUERY, like its single-entity sibling: this runs on the authorization path (group
        // inheritance for a policy binding), and the kind/id-only finder cannot use an index that leads with
        // org_id — so it scanned the ABAC hot table on every authenticated request.
        List<EntityAttribute> rows = tier == null
                ? attributes.findByOrgIdIsNullAndEntityKindAndEntityIdIn(kind, entityIds)
                : attributes.findByOrgIdAndEntityKindAndEntityIdIn(tier, kind, entityIds);
        Set<Attribute> union = new LinkedHashSet<>();
        rows.forEach(row -> union.add(new Attribute(row.getAttrKey(), row.getAttrValue())));
        return List.copyOf(union);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> attributesOfInTier(EntityKind kind, String entityId) {
        UUID tier = tierGuard.currentTier();
        // Scoped in the QUERY rather than filtered after: every index on this table leads with org_id, so the
        // kind/id-only finder scans. It is the ABAC hot table and grows with users x attributes.
        List<EntityAttribute> rows = tier == null
                ? attributes.findByOrgIdIsNullAndEntityKindAndEntityIdOrderByAttrKey(kind, entityId)
                : attributes.findByOrgIdAndEntityKindAndEntityIdOrderByAttrKey(tier, kind, entityId);
        return rows.stream().map(row -> new Attribute(row.getAttrKey(), row.getAttrValue())).toList();
    }

    @Override
    @Transactional
    public void set(EntityKind kind, String entityId, String key, String value) {
        requireWritable(kind, key);
        UUID tier = tierGuard.currentTier();
        List<EntityAttribute> rows = ownRows(kind, entityId, key, tier);
        List<EntityAttribute> stale = rows.stream().filter(row -> !row.getAttrValue().equals(value)).toList();
        boolean present = rows.stream().anyMatch(row -> row.getAttrValue().equals(value));
        if (!stale.isEmpty()) {
            attributes.deleteAll(stale); // drop the key's other values so the set becomes exactly {value}
        }
        if (!present) {
            attributes.save(new EntityAttribute(kind, entityId, key, value, tier));
        }
        if (!stale.isEmpty() || !present) {
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier)); // only when a row changed
        }
    }

    @Override
    @Transactional
    public void add(EntityKind kind, String entityId, String key, String value) {
        requireWritable(kind, key);
        UUID tier = tierGuard.currentTier();
        if (!ownValueExists(kind, entityId, key, value, tier)) { // idempotent — never a duplicate (key,value)
            attributes.save(new EntityAttribute(kind, entityId, key, value, tier));
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier));
        }
    }

    @Override
    @Transactional
    public void addAll(EntityKind kind, String entityId, Map<String, List<String>> values) {
        // Ownership is per key (a group tag re-checks the USER key too); the grant ceiling is asked ONCE over
        // the whole key set. The guard reaches the mapping rules, so asking it per key re-ran that lookup for
        // every attribute of a bulk write — a bulk import names many keys at once.
        values.keySet().forEach(key -> requireLocallyOwned(kind, key));
        requireMayDecideGrants(kind, values.keySet());
        requireMayDecidePolicy(kind, values.keySet());
        UUID tier = tierGuard.currentTier();
        boolean wrote = false;
        for (Map.Entry<String, List<String>> attribute : values.entrySet()) {
            wrote |= writeValues(kind, entityId, attribute.getKey(), attribute.getValue(), tier);
        }
        if (wrote) {
            // One event for the whole write, for the reason removeAll gives: the listener re-evaluates every
            // mapping rule for this entity, and once per value repeats that work for one logical change.
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier));
        }
    }

    /** Every non-blank value under one key; true when any row was actually inserted. */
    private boolean writeValues(EntityKind kind, String entityId, String key, List<String> values, UUID tier) {
        boolean wrote = false;
        for (String value : values) {
            if (value != null && !value.isBlank() && !ownValueExists(kind, entityId, key, value, tier)) {
                attributes.save(new EntityAttribute(kind, entityId, key, value, tier));
                wrote = true;
            }
        }
        return wrote;
    }

    @Override
    @Transactional
    public void removeValue(EntityKind kind, String entityId, String key, String value) {
        requireRemovable(kind, key);
        UUID tier = tierGuard.currentTier();
        List<EntityAttribute> rows = ownRows(kind, entityId, key, tier).stream()
                .filter(row -> row.getAttrValue().equals(value)).toList();
        if (!rows.isEmpty()) {
            attributes.deleteAll(rows);
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier));
        }
    }

    @Override
    @Transactional
    public void remove(EntityKind kind, String entityId, String key) {
        requireRemovable(kind, key);
        UUID tier = tierGuard.currentTier();
        List<EntityAttribute> rows = ownRows(kind, entityId, key, tier);
        if (!rows.isEmpty()) {
            attributes.deleteAll(rows); // all of the key's values in this tier
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier)); // only when a row changed
        }
    }

    @Override
    @Transactional
    public void removeAll(EntityKind kind, String entityId, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        // Refuse the whole set before deleting any of it: ownership per key, the policy ceiling once for all of
        // them (a binding removal loosens posture just as a single remove does).
        keys.forEach(key -> requireLocallyOwned(kind, key));
        List<String> distinct = keys.stream().distinct().toList();
        requireMayDecidePolicy(kind, distinct);
        UUID tier = tierGuard.currentTier();
        // One statement, and it returns the row count. A derived delete would SELECT every row and issue a
        // DELETE each; the count is what matters more, because this retirement can retract an ABAC-granted
        // role and a delete that matched nothing must not look like one that worked.
        int removed = tier == null
                ? attributes.deleteKeysGlobally(kind, entityId, distinct)
                : attributes.deleteKeysInOrg(tier, kind, entityId, distinct);
        if (removed > 0) {
            // One event for the whole retirement: the listener re-evaluates every mapping rule for this user,
            // and doing that once per key would repeat the same work for one logical change.
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> entityIdsWith(EntityKind kind, String key, String value) {
        return new HashSet<>(attributes.findEntityIds(kind, key, value));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> entityIdsWithInTier(EntityKind kind, String key, String value) {
        UUID tier = tierGuard.currentTier();
        return new HashSet<>(tier == null
                ? attributes.findEntityIdsGlobal(kind, key, value)
                : attributes.findEntityIdsInOrg(kind, key, value, tier));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> entityIdsWithKeyInTier(EntityKind kind, String key) {
        UUID tier = tierGuard.currentTier();
        return new HashSet<>(tier == null
                ? attributes.findEntityIdsWithKeyGlobal(kind, key)
                : attributes.findEntityIdsWithKeyInOrg(kind, key, tier));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> entityIdsWithAnyValueInTier(EntityKind kind, String key, Collection<String> values) {
        if (values.isEmpty()) {
            return Set.of();
        }
        UUID tier = tierGuard.currentTier();
        return new HashSet<>(tier == null
                ? attributes.findEntityIdsWithValueInGlobal(kind, key, values)
                : attributes.findEntityIdsWithValueInOrg(kind, key, values, tier));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> entityIdsWithValueContainingInTier(EntityKind kind, String key, String substring) {
        UUID tier = tierGuard.currentTier();
        String pattern = "%" + likeEscape(substring) + "%";
        return new HashSet<>(tier == null
                ? attributes.findEntityIdsWithValueLikeGlobal(kind.name(), key, pattern)
                : attributes.findEntityIdsWithValueLikeInOrg(kind.name(), key, pattern, tier));
    }

    /** Escape LIKE wildcards so a caller's substring matches literally (backslash first, then % and _). */
    private String likeEscape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The acting tier's OWN rows for this key (never a shadowed global), so an edit touches only the tier's — a
     *  key may now hold several values, so this is a list. */
    @Override
    @Transactional
    public void applyFromDirectory(EntityKind kind, String entityId, String key, Collection<String> values) {
        requireDirectoryOwned(kind, key);
        UUID tier = tierGuard.currentTier();
        List<EntityAttribute> rows = ownRows(kind, entityId, key, tier);
        Set<String> wanted = Set.copyOf(values);
        List<EntityAttribute> stale = rows.stream()
                .filter(row -> !wanted.contains(row.getAttrValue())).toList();
        Set<String> present = rows.stream().map(EntityAttribute::getAttrValue).collect(Collectors.toSet());
        if (!stale.isEmpty()) {
            attributes.deleteAll(stale);
        }
        wanted.stream().filter(value -> !present.contains(value))
                .forEach(value -> attributes.save(new EntityAttribute(kind, entityId, key, value, tier)));
        if (!stale.isEmpty() || !present.containsAll(wanted)) {
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier));
        }
    }

    /**
     * An administrator may not edit an attribute a directory owns — the next sync would overwrite the edit, and
     * a change that silently disappears hours later is worse than one that is refused now.
     */
    /**
     * The two questions a local write has to pass: does a directory own this key, and does writing it decide a
     * privilege the actor could not confer by hand. Kept together because every write path asks both, and one
     * of them was added long after the other.
     */
    private void requireWritable(EntityKind kind, String key) {
        requireLocallyOwned(kind, key);
        requireMayDecideGrants(kind, Set.of(key));
        requireMayDecidePolicy(kind, Set.of(key));
    }

    /**
     * Removal carries no mapping-rule GRANT ceiling: a grant is on the PRESENCE of a value (operators are
     * positive-only — {@link com.example.sso.mapping.MappingRuleService} rejects {@code NOT_EXISTS}/
     * {@code NOT_EQUALS} at creation), so taking a value away can only retract a grant, never make one, and an
     * administrator must always be able to retract. But a policy binding is the OPPOSITE polarity: it tightens
     * on presence, so removing the value it reads drops the target back to the looser org default — a posture
     * move that needs the same policy authority a write does. So removal keeps the policy ceiling, not the grant one.
     */
    private void requireRemovable(EntityKind kind, String key) {
        requireLocallyOwned(kind, key);
        requireMayDecidePolicy(kind, Set.of(key));
    }

    /**
     * A mapping rule can confer a role on whoever carries a value, so writing the key it reads is a grant by
     * another route. Group membership works the same way and is already refused unless the actor could confer
     * the group's roles; this is that rule, for the other route. The ceiling is asked over the whole key set in
     * one call — the guard reaches the mapping rules, so a per-key call re-ran that lookup for every key.
     *
     * <p>USER and GROUP only: a group tag is unioned into every member's attributes and tested by the same
     * predicate, so it reaches rules exactly as a user attribute does. Application and resource tags are not.
     */
    private void requireMayDecideGrants(EntityKind kind, Collection<String> keys) {
        if (kind != EntityKind.USER && kind != EntityKind.GROUP) {
            return;
        }
        Set<String> beyond = grantGuard.getObject().keysBeyondAuthority(keys);
        if (!beyond.isEmpty()) {
            throw ForbiddenException.of("metadata.attribute.grantGoverned", beyond.iterator().next());
        }
    }

    /**
     * A policy binding decides a live session's auth/session policy by testing an attribute, re-read every
     * request, so writing OR removing the value it reads moves the target's posture. Whoever moves it must hold
     * the authority to set that policy themselves — the same ceiling {@code AttributeKeyPolicyGuard} enforces
     * when a source is aimed at the key. Asked over the whole key set in one call, for the reason
     * {@link #requireMayDecideGrants} gives.
     *
     * <p>USER and GROUP only, and the key NAME is enough for both: a binding condition carries only the key, and
     * a group tag is unioned into every member's attributes, so a binding reading the key matches whether it was
     * set on a user or on a group they belong to.
     */
    private void requireMayDecidePolicy(EntityKind kind, Collection<String> keys) {
        if (kind != EntityKind.USER && kind != EntityKind.GROUP) {
            return;
        }
        Set<String> beyond = policyGuard.keysBeyondAuthority(keys);
        if (!beyond.isEmpty()) {
            throw ForbiddenException.of("metadata.attribute.policyGoverned", beyond.iterator().next());
        }
    }

    private void requireLocallyOwned(EntityKind kind, String key) {
        refuseIfSourceOwned(kind, key);
        if (kind == EntityKind.GROUP) {
            // A group tag is unioned into every member's attributes and tested by the SAME predicate, with no
            // kind in the comparison (PolicyBindingResolverImpl.effectiveAttributes). Tagging a group with a
            // directory-owned USER key would forge that key for all its members, so the ownership the USER
            // branch enforces has to hold here too — otherwise it holds on one path and not the other.
            refuseIfSourceOwned(EntityKind.USER, key);
        }
    }

    private void refuseIfSourceOwned(EntityKind kind, String key) {
        definitions.definitionOf(kind, key)
                .filter(definition -> !definition.locallyEditable())
                .ifPresent(definition -> {
                    throw ConflictException.of("attribute.directoryOwned", key);
                });
    }

    /**
     * The mirror image, and the half that is easy to forget: a sync may only write what its schema says it
     * owns. Without this a mis-mapped connector silently eats values an administrator owns, and an undeclared
     * key would let a sync invent schema by writing to it.
     */
    private void requireDirectoryOwned(EntityKind kind, String key) {
        AttributeDefinition definition = definitions.definitionOf(kind, key)
                .orElseThrow(() -> ConflictException.of("attribute.notDeclared", key));
        if (definition.locallyEditable()) {
            throw ConflictException.of("attribute.locallyOwned", key);
        }
    }

    private List<EntityAttribute> ownRows(EntityKind kind, String entityId, String key, UUID tier) {
        return tier == null
                ? attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgIdIsNull(kind, entityId, key)
                : attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgId(kind, entityId, key, tier);
    }

    /** Whether the acting tier already holds this exact (key, value) — the idempotency guard for {@link #add}. */
    private boolean ownValueExists(EntityKind kind, String entityId, String key, String value, UUID tier) {
        return tier == null
                ? attributes.existsByEntityKindAndEntityIdAndAttrKeyAndAttrValueAndOrgIdIsNull(
                        kind, entityId, key, value)
                : attributes.existsByEntityKindAndEntityIdAndAttrKeyAndAttrValueAndOrgId(
                        kind, entityId, key, value, tier);
    }
}
