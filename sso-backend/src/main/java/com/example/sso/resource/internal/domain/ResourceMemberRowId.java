package com.example.sso.resource.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import java.io.Serializable;
import java.util.UUID;

/**
 * Composite identifier of {@link ResourceMemberRow} — the owning resource plus the embedded
 * {@link ResourceMember} value object (the whole {@code resource_member} row is its own key). Keeping
 * the member as an {@code @Embedded} value keeps its behaviour/validation bounded inside the key.
 */
@Embeddable
public record ResourceMemberRowId(
        @Column(name = "resource_id", nullable = false) UUID resourceId,
        @Embedded ResourceMember member) implements Serializable {
}
