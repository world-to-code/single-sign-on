package com.example.sso.metadata.internal.domain;

import com.example.sso.metadata.EntityKind;
import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One metadata attribute (key/value) on an entity, org-scoped ({@code orgId} null = global). The entity is
 * referenced by {@link EntityKind} + its id as text (never a cross-module entity), keeping this module free
 * of the user/group/app/resource types.
 */
@Entity
@Table(name = "entity_attribute")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class EntityAttribute extends AuditedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_kind", nullable = false, length = 16)
    private EntityKind entityKind;

    @Column(name = "entity_id", nullable = false, length = 255)
    private String entityId;

    @Column(name = "attr_key", nullable = false, length = 64)
    private String attrKey;

    @Column(name = "attr_value", nullable = false, length = 255)
    private String attrValue;

    @Column(name = "org_id")
    private UUID orgId;

    public EntityAttribute(EntityKind entityKind, String entityId, String attrKey, String attrValue, UUID orgId) {
        this.entityKind = entityKind;
        this.entityId = entityId;
        this.attrKey = attrKey;
        this.attrValue = attrValue;
        this.orgId = orgId;
    }

    /** Intention-revealing value change (no setter); the key/entity/tier are fixed at creation. */
    public void changeValue(String value) {
        this.attrValue = value;
    }
}
