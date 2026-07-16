package com.example.sso.resource.catalog;

import java.util.UUID;

/**
 * Published after a resource is deleted, so other modules can drop what referenced it — e.g. auto-mapping rules
 * targeting the resource (its member rows are already gone with the resource; this only clears the now-dangling
 * rules). Mirrors {@code GroupDeletedEvent} / {@code RoleDeletedEvent}.
 */
public record ResourceDeletedEvent(UUID resourceId) {
}
