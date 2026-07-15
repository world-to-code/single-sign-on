package com.example.sso.metadata;

import java.util.UUID;

/**
 * Published after an entity's metadata attribute is set or removed, in the acting tier ({@code orgId}, null for
 * a platform/global write). Lets downstream features (e.g. metadata-driven auto-mapping) re-evaluate the entity
 * without the metadata module knowing about them. The value is intentionally omitted: a listener re-reads the
 * entity's effective attributes in {@code orgId}'s scope rather than trusting a value carried across the commit.
 */
public record EntityAttributeChangedEvent(EntityKind kind, String entityId, UUID orgId) {
}
