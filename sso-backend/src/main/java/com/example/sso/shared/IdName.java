package com.example.sso.shared;

import java.util.UUID;

/**
 * Lightweight (id, name) projection for lookups that only need a display label — avoids loading full
 * entities (and their EAGER associations) just to resolve a name.
 */
public interface IdName {
    UUID getId();

    String getName();
}
