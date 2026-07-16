package com.example.sso.mapping;

import java.util.UUID;

/**
 * Re-validates, at async materialize time, that a mapping rule's author STILL holds the authority the manual
 * create/update path demanded — the mapping module owns this port so it can make the check without depending on
 * the admin module (which implements it); the grant-authority policy stays in admin, out of a module cycle.
 *
 * <p>Runs OFF the request thread (no {@code SecurityContext}), so the author is addressed by id and their
 * effective authority is resolved fresh, not read from a session. Fails CLOSED: an unknown/deleted author, or
 * one who has since lost the authority, returns {@code false} and the grant is skipped.
 */
public interface MappingTargetAuthority {

    /** Whether {@code authorId}, with their CURRENT effective authority, may still grant {@code targetId} of
     *  {@code kind} — the same rule the manual path enforces. */
    boolean authorMayAssign(UUID authorId, MappingTargetKind kind, UUID targetId);
}
