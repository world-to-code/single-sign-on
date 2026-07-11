package com.example.sso.resource.internal.catalog.application;

/** A delegation grant on a {@link ResourceView} (grantee user id + tier ADMIN | VIEWER). */
public record ResourceGrantView(String userId, String tier) {
}
