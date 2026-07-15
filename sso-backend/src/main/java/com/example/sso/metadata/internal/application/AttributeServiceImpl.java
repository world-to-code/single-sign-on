package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.EntityAttribute;
import com.example.sso.metadata.internal.domain.EntityAttributeRepository;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

    @Override
    @Transactional(readOnly = true)
    public List<Attribute> attributesOf(EntityKind kind, String entityId) {
        UUID tier = tierGuard.currentTier();
        Map<String, String> byKey = new LinkedHashMap<>();
        List<EntityAttribute> rows = attributes.findByEntityKindAndEntityIdOrderByAttrKey(kind, entityId);
        rows.stream().filter(row -> row.getOrgId() == null)
                .forEach(row -> byKey.put(row.getAttrKey(), row.getAttrValue()));       // global first
        rows.stream().filter(row -> Objects.equals(row.getOrgId(), tier))
                .forEach(row -> byKey.put(row.getAttrKey(), row.getAttrValue()));       // own shadows global
        return byKey.entrySet().stream().sorted(Map.Entry.comparingByKey()) // the merge can disturb key order
                .map(e -> new Attribute(e.getKey(), e.getValue())).toList();
    }

    @Override
    @Transactional
    public void set(EntityKind kind, String entityId, String key, String value) {
        UUID tier = tierGuard.currentTier();
        ownAttribute(kind, entityId, key, tier).ifPresentOrElse(
                row -> row.changeValue(value), // dirty-checked update of the tier's own row
                () -> attributes.save(new EntityAttribute(kind, entityId, key, value, tier)));
    }

    @Override
    @Transactional
    public void remove(EntityKind kind, String entityId, String key) {
        UUID tier = tierGuard.currentTier();
        ownAttribute(kind, entityId, key, tier).ifPresent(attributes::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> entityIdsWith(EntityKind kind, String key, String value) {
        return new HashSet<>(attributes.findEntityIds(kind, key, value));
    }

    /** The acting tier's OWN row for this key (never a shadowed global), so an upsert/remove touches only it. */
    private Optional<EntityAttribute> ownAttribute(EntityKind kind, String entityId, String key, UUID tier) {
        return tier == null
                ? attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgIdIsNull(kind, entityId, key)
                : attributes.findByEntityKindAndEntityIdAndAttrKeyAndOrgId(kind, entityId, key, tier);
    }
}
