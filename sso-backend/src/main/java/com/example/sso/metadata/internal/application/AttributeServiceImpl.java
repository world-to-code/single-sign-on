package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Attribute;
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
        UUID tier = tierGuard.currentTier();
        boolean wrote = false;
        for (Map.Entry<String, List<String>> attribute : values.entrySet()) {
            requireWritable(kind, attribute.getKey());
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
        keys.forEach(key -> requireRemovable(kind, key)); // refuse the whole set before deleting any of it
        UUID tier = tierGuard.currentTier();
        List<String> distinct = keys.stream().distinct().toList();
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
        requireMayDecideGrants(kind, key, false);
    }

    /** Removing is bounded only by the rules that confer on a key's ABSENCE — see AttributeValueGrantGuard. */
    private void requireRemovable(EntityKind kind, String key) {
        requireLocallyOwned(kind, key);
        requireMayDecideGrants(kind, key, true);
    }

    /**
     * A mapping rule can confer a role on whoever carries a value, so writing the key it reads is a grant by
     * another route. Group membership works the same way and is already refused unless the actor could confer
     * the group's roles; this is that rule, for the other route.
     *
     * <p>USER and GROUP only: a group tag is unioned into every member's attributes and tested by the same
     * predicate, so it reaches rules exactly as a user attribute does. Application and resource tags are not.
     */
    private void requireMayDecideGrants(EntityKind kind, String key, boolean removing) {
        if (kind != EntityKind.USER && kind != EntityKind.GROUP) {
            return;
        }
        AttributeValueGrantGuard guard = grantGuard.getObject();
        Set<String> beyond = removing
                ? guard.keysBeyondAuthorityToRemove(Set.of(key))
                : guard.keysBeyondAuthority(Set.of(key));
        if (!beyond.isEmpty()) {
            throw ForbiddenException.of("metadata.attribute.grantGoverned", key);
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
