package com.example.sso.resource.internal.catalog.application;

/**
 * A leaf member of a {@link ResourceDetailView} with its display label resolved (group/app name or
 * username). {@code label} is null when the target no longer resolves (a stale row awaiting cleanup).
 */
public record ResourceMemberDetailView(String memberType, String memberId, String label) {
}
