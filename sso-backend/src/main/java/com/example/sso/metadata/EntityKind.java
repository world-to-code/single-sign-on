package com.example.sso.metadata;

/** The kinds of entity that can carry metadata attributes. The entity is referenced by (kind, id-as-string). */
public enum EntityKind {
    USER,
    GROUP,
    APPLICATION,
    RESOURCE
}
