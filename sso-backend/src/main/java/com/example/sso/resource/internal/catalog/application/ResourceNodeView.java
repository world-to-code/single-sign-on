package com.example.sso.resource.internal.catalog.application;

/** A resource node reference (id + display name) — a parent or child in a {@link ResourceDetailView}. */
public record ResourceNodeView(String id, String name) {
}
