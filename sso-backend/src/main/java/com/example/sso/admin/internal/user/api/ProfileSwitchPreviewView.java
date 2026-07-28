package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.user.application.ProfileSwitchPreview;
import java.util.List;

/**
 * What a profile move would cost, as the console receives it.
 *
 * <p>{@code blocked} is a DECLARED field here rather than the serialized form of a derived accessor. The
 * console's confirm button is disabled on it, so the whole disclosure before a role-retracting deletion rides
 * on that name — and a derived accessor's name is not a contract: renaming {@code isBlocked()} to
 * {@code blocked()} is a cosmetic Java edit that would silently drop the field, leave the client reading
 * {@code undefined}, and re-open the button on moves the server then refuses. Declaring it also makes it
 * assertable in a controller test, which a Jackson-inferred property never was.
 *
 * @param removedKeys the attributes the move would delete
 * @param blockedKeys the subset a directory owns, which an administrator may not delete
 */
public record ProfileSwitchPreviewView(List<String> removedKeys, List<String> blockedKeys,
                                       boolean externallyManaged, boolean blocked) {

    public static ProfileSwitchPreviewView of(ProfileSwitchPreview preview) {
        return new ProfileSwitchPreviewView(preview.removedKeys(), preview.blockedKeys(),
                preview.externallyManaged(), preview.isBlocked());
    }
}
