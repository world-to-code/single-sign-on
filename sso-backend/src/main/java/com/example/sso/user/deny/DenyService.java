package com.example.sso.user.deny;

import java.util.UUID;

/**
 * Authors and lifts negative permissions. Authoring and lifting are both authorized through {@link DenyAuthority}
 * (grant-symmetric, subject-scoped, dominance-gated), stamp/read the author provenance for the lift guard, and
 * TERMINATE the affected subjects' live sessions so an access-tightening deny (or its removal) takes effect
 * without waiting for re-login. A deny is stored per tier ({@link DenySubjectKind}); creating one is idempotent.
 */
public interface DenyService {

    /** Authors the deny (or returns the existing one's id if identical) and revokes the affected sessions. */
    UUID create(DenySpec spec);

    /** Lifts (removes) the deny by id and revokes the affected sessions. A no-op if it no longer exists. */
    void lift(UUID denyId, DenySubjectKind kind);
}
