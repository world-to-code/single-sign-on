package com.example.sso.metadata;

import java.util.Set;
import java.util.UUID;

/**
 * Who vouched for the identity sources that can fill a set of attributes.
 *
 * <p>Controlling a source is a way to decide who satisfies someone else's rule without holding any authority
 * over its outcome — point a connector at a directory you run, assert the matching value for yourself, and
 * collect whatever an existing, entirely legitimate rule grants. So before a source-filled attribute is
 * allowed to drive anything, the people who aimed those sources have to be accounted for.
 *
 * @param configurators the administrators who last configured each source filling those attributes
 * @param complete      false when a source has no recorded configurator — SCIM and CSV push to us and have
 *                      nobody to attribute, so the caller must treat the answer as unusable rather than act
 *                      on the half it can see
 */
public record AttributeSourceAuthors(Set<UUID> configurators, boolean complete) {

    public static AttributeSourceAuthors none() {
        return new AttributeSourceAuthors(Set.of(), true);
    }

    /** Whether every source filling those attributes is accounted for AND there is at least one. */
    public boolean fullyAttributed() {
        return complete && !configurators.isEmpty();
    }
}
