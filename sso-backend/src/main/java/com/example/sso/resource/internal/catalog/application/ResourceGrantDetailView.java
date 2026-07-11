package com.example.sso.resource.internal.catalog.application;

/**
 * A delegation grant on a {@link ResourceDetailView} with the grantee's username resolved.
 * {@code username} is null when the user no longer resolves.
 */
public record ResourceGrantDetailView(String userId, String username, String tier) {
}
