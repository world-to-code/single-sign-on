package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityAttributeChangedEvent;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.EntityAttribute;
import com.example.sso.metadata.internal.domain.EntityAttributeRepository;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
    private final OrgTierGuard tierGuard;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> attributesOf(EntityKind kind, String entityId) {
        UUID tier = tierGuard.currentTier();
        return effectiveOf(attributes.findByEntityKindAndEntityIdOrderByAttrKey(kind, entityId), tier)
                .entrySet().stream().sorted(Map.Entry.comparingByKey()) // the merge can disturb key order
                .map(e -> new Attribute(e.getKey(), e.getValue())).toList();
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
        // Union across the entities: a (key,value) any of them carries, with own-shadows-global applied per entity.
        Set<Attribute> union = new LinkedHashSet<>();
        byEntity.values().forEach(rows ->
                effectiveOf(rows, tier).forEach((key, value) -> union.add(new Attribute(key, value))));
        return List.copyOf(union);
    }

    /** One entity's EFFECTIVE key→value map: global rows first, then the acting tier's own rows shadow them. */
    private Map<String, String> effectiveOf(List<EntityAttribute> rows, UUID tier) {
        Map<String, String> byKey = new LinkedHashMap<>();
        rows.stream().filter(row -> row.getOrgId() == null)
                .forEach(row -> byKey.put(row.getAttrKey(), row.getAttrValue()));       // global first
        rows.stream().filter(row -> Objects.equals(row.getOrgId(), tier))
                .forEach(row -> byKey.put(row.getAttrKey(), row.getAttrValue()));       // own shadows global
        return byKey;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> unionAttributesOfInTier(EntityKind kind, Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        UUID tier = tierGuard.currentTier();
        Set<Attribute> union = new LinkedHashSet<>();
        attributes.findByEntityKindAndEntityIdIn(kind, entityIds).stream()
                .filter(row -> Objects.equals(row.getOrgId(), tier)) // own-tier rows only, no inherited globals
                .forEach(row -> union.add(new Attribute(row.getAttrKey(), row.getAttrValue())));
        return List.copyOf(union);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> attributesOfInTier(EntityKind kind, String entityId) {
        UUID tier = tierGuard.currentTier();
        return attributes.findByEntityKindAndEntityIdOrderByAttrKey(kind, entityId).stream()
                .filter(row -> Objects.equals(row.getOrgId(), tier)) // own-tier rows only, no inherited globals
                .map(row -> new Attribute(row.getAttrKey(), row.getAttrValue())).toList();
    }

    @Override
    @Transactional
    public void set(EntityKind kind, String entityId, String key, String value) {
        UUID tier = tierGuard.currentTier();
        ownAttribute(kind, entityId, key, tier).ifPresentOrElse(
                row -> row.changeValue(value), // dirty-checked update of the tier's own row
                () -> attributes.save(new EntityAttribute(kind, entityId, key, value, tier)));
        events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier));
    }

    @Override
    @Transactional
    public void remove(EntityKind kind, String entityId, String key) {
        UUID tier = tierGuard.currentTier();
        ownAttribute(kind, entityId, key, tier).ifPresent(row -> {
            attributes.delete(row);
            events.publishEvent(new EntityAttributeChangedEvent(kind, entityId, tier)); // only when a row changed
        });
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

    /** The acting tier's OWN row for this key (never a shadowed global), so an upsert/remove touches only it. */
    private Optional<EntityAttribute> ownAttribute(EntityKind kind, String entityId, String key, UUID tier) {
        return tier == null
                ? attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgIdIsNull(kind, entityId, key)
                : attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgId(kind, entityId, key, tier);
    }
}
