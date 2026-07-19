package com.example.sso.metadata.internal.application;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeDefinitionSpec;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.internal.domain.AttributeDefinitionEntity;
import com.example.sso.metadata.internal.domain.AttributeDefinitionRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Default {@link AttributeDefinitionService}. Every read and write names the acting tier explicitly rather than
 * relying on ambient RLS — a definition belongs to exactly one tier and is not inherited, so a tenant neither
 * sees nor edits the platform tier's schema, and a platform caller edits only the global one.
 */
@Service
@RequiredArgsConstructor
class AttributeDefinitionServiceImpl implements AttributeDefinitionService {

    /** The same shape {@code entity_attribute} accepts — a key must be referenceable by a policy predicate. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private final AttributeDefinitionRepository repository;
    private final OrgTierGuard tierGuard;

    @Override
    @Transactional(readOnly = true)
    public List<AttributeDefinition> definitionsFor(EntityKind kind) {
        UUID tier = tierGuard.currentTier();
        List<AttributeDefinitionEntity> rows = tier == null
                ? repository.findByOrgIdIsNullAndEntityKindOrderBySortOrderAscAttrKeyAsc(kind)
                : repository.findByOrgIdAndEntityKindOrderBySortOrderAscAttrKeyAsc(tier, kind);
        return rows.stream().map(this::toDefinition).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AttributeDefinition> definitionOf(EntityKind kind, String key) {
        return find(kind, key).map(this::toDefinition);
    }

    @Override
    @Transactional
    public AttributeDefinition save(AttributeDefinitionSpec spec) {
        validate(spec);
        UUID tier = tierGuard.currentTier();
        AttributeDefinitionEntity existing = find(spec.entityKind(), spec.key()).orElse(null);
        if (existing != null) {
            // The key is the identity: live attribute rows, mapping rules and policy bindings all reference it
            // as a bare string with no foreign key back here, so it is redefined in place, never re-keyed.
            existing.redefine(spec.displayName().trim(), spec.description(), spec.dataType(), spec.enumValues(),
                    spec.multiValued(), spec.required(), spec.source(), spec.sortOrder());
            return toDefinition(existing);
        }
        return toDefinition(repository.save(AttributeDefinitionEntity.create(tier, spec.entityKind(),
                spec.key().trim(), spec.displayName().trim(), spec.description(), spec.dataType(),
                spec.enumValues(), spec.multiValued(), spec.required(), spec.source(), spec.sortOrder())));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        UUID tier = tierGuard.currentTier();
        AttributeDefinitionEntity row = (tier == null
                ? repository.findByIdAndOrgIdIsNull(id)
                : repository.findByIdAndOrgId(id, tier))
                .orElseThrow(() -> new NotFoundException("Attribute definition not found"));
        // Attribute VALUES deliberately survive: a definition is a catalog entry, and deleting it must not
        // silently strip data that mapping rules and policy bindings are still matching on.
        repository.delete(row);
    }

    private Optional<AttributeDefinitionEntity> find(EntityKind kind, String key) {
        UUID tier = tierGuard.currentTier();
        String attrKey = key == null ? "" : key.trim();
        return tier == null
                ? repository.findByOrgIdIsNullAndEntityKindAndAttrKey(kind, attrKey)
                : repository.findByOrgIdAndEntityKindAndAttrKey(tier, kind, attrKey);
    }

    private void validate(AttributeDefinitionSpec spec) {
        String key = spec.key() == null ? "" : spec.key().trim();
        if (!KEY.matcher(key).matches()) {
            throw BadRequestException.of("attribute.definition.key.invalid", key);
        }
        if (!StringUtils.hasText(spec.displayName())) {
            throw BadRequestException.of("attribute.definition.displayName.required");
        }
        boolean hasValues = spec.enumValues() != null && !spec.enumValues().isEmpty();
        if (spec.dataType().requiresEnumValues() && !hasValues) {
            throw BadRequestException.of("attribute.definition.enumValues.required");
        }
        // Values on a non-ENUM would be stored and then silently ignored everywhere; refuse rather than keep
        // something inert that an admin will later believe is being enforced.
        if (!spec.dataType().requiresEnumValues() && hasValues) {
            throw BadRequestException.of("attribute.definition.enumValues.unsupported");
        }
    }

    private AttributeDefinition toDefinition(AttributeDefinitionEntity row) {
        List<String> values = row.getDataType() == AttributeDataType.ENUM && row.getEnumValues() != null
                ? List.copyOf(row.getEnumValues())
                : List.of();
        return new AttributeDefinition(row.getId(), row.getEntityKind(), row.getAttrKey(), row.getDisplayName(),
                row.getDescription(), row.getDataType(), values, row.isMultiValued(), row.isRequired(),
                row.getSource(), row.getSortOrder());
    }
}
