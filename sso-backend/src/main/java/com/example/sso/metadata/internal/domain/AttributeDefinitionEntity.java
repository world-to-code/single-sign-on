package com.example.sso.metadata.internal.domain;

import com.example.sso.metadata.AttributeDataType;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One declared attribute of a tier's profile schema. Named …Entity because the module root already exposes the
 * {@code AttributeDefinition} record that leaves this module — the entity never does.
 *
 * <p>{@code attrKey} is immutable once created: it is what live attribute rows, mapping rules and policy
 * bindings reference, and none of them hold a foreign key back to here.
 */
@Entity
@Table(name = "attribute_definition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttributeDefinitionEntity extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_kind", nullable = false, length = 16)
    private EntityKind entityKind;

    @Column(name = "attr_key", nullable = false, length = 64)
    private String attrKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 16)
    private AttributeDataType dataType;

    /** The permitted values (a text[] column); populated only for {@link AttributeDataType#ENUM}. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "enum_values")
    private List<String> enumValues;

    @Column(name = "multi_valued", nullable = false)
    private boolean multiValued;

    @Column(nullable = false)
    private boolean required;

    /** Who owns the VALUES of this attribute — a directory-owned one is read-only in the console. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttributeSource source;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public static AttributeDefinitionEntity create(UUID orgId, EntityKind entityKind, String attrKey,
            String displayName, String description, AttributeDataType dataType, List<String> enumValues,
            boolean multiValued, boolean required, AttributeSource source, int sortOrder) {
        AttributeDefinitionEntity definition = new AttributeDefinitionEntity();
        definition.orgId = orgId;
        definition.entityKind = entityKind;
        definition.attrKey = attrKey;
        definition.apply(displayName, description, dataType, enumValues, multiValued, required, source, sortOrder);
        return definition;
    }

    /** Redefines everything but the identity of the attribute (tier, kind and key are fixed). */
    public void redefine(String displayName, String description, AttributeDataType dataType,
            List<String> enumValues, boolean multiValued, boolean required, AttributeSource source, int sortOrder) {
        apply(displayName, description, dataType, enumValues, multiValued, required, source, sortOrder);
    }

    private void apply(String displayName, String description, AttributeDataType dataType,
            List<String> enumValues, boolean multiValued, boolean required, AttributeSource source, int sortOrder) {
        this.displayName = displayName;
        this.description = description;
        this.dataType = dataType;
        this.enumValues = enumValues;
        this.multiValued = multiValued;
        this.required = required;
        this.source = source;
        this.sortOrder = sortOrder;
    }
}
