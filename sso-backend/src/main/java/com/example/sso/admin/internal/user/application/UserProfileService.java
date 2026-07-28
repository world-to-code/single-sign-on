package com.example.sso.admin.internal.user.application;

import java.util.Collection;
import java.util.UUID;

/** Binds a user to a profile, and reports what that would cost before doing it. */
public interface UserProfileService {

    /** What moving {@code userId} onto {@code profileId} would delete. Writes nothing. */
    ProfileSwitchPreview preview(UUID userId, UUID profileId);

    /**
     * Moves the user onto {@code profileId}, deleting the attributes it does not declare.
     *
     * <p>Only a locally-managed user may move: one provisioned by a directory or SCIM has its attributes
     * owned upstream, and the next sync would fight whatever this changed.
     *
     * <p>{@code confirmedKeys} is what {@link #preview} told the administrator would go, echoed back. When it
     * is given and no longer matches, the move is refused rather than performed against a different cost: the
     * preview and the write are separate requests, and a sync or a schema edit in between can change the
     * answer. Null accepts whatever the move computes — for a caller that never previewed.
     */
    void switchTo(UUID userId, UUID profileId, Collection<String> confirmedKeys);
}
