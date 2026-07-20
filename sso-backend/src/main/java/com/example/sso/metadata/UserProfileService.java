package com.example.sso.metadata;

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
     */
    void switchTo(UUID userId, UUID profileId);
}
