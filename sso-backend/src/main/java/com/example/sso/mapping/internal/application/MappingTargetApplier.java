package com.example.sso.mapping.internal.application;

import com.example.sso.mapping.MappingTargetKind;
import java.util.Set;
import java.util.UUID;

/**
 * Per-{@link MappingTargetKind} strategy for materializing/retracting a mapping rule's assignment. The evaluator
 * owns the predicate matching, provenance and audit; each applier owns only the actual grant/revoke against its
 * target subsystem (group membership, role membership, …) and the target's tier-validation and display label.
 * Adding a new kind = one new applier, not edits scattered across the evaluator and service.
 */
interface MappingTargetApplier {

    MappingTargetKind kind();

    /** Validate the target exists in the acting tier and is assignable (else a non-revealing 400); return its name. */
    String validateInTier(UUID targetId);

    /** The target's display name, or {@code null} if it no longer resolves. */
    String label(UUID targetId);

    /** Grant the assignment to one user (idempotent). */
    void assign(UUID targetId, UUID userId);

    /** Grant the assignment to a whole cohort (idempotent) — batched where the target subsystem supports it. */
    void assignAll(UUID targetId, Set<UUID> userIds);

    /** Revoke the assignment from one user (idempotent). */
    void unassign(UUID targetId, UUID userId);
}
