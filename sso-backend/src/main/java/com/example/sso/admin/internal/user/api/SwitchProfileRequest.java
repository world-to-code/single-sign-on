package com.example.sso.admin.internal.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * The profile to move a user onto, and the cost the administrator was shown when they confirmed.
 *
 * <p>Deliberately a body, not a query parameter: this write deletes data.
 *
 * @param profileId    the target profile
 * @param confirmedKeys the attributes the preview said would be deleted, echoed back. REQUIRED: preview and
 *                      confirm are independent requests and the answer can change between them — a directory
 *                      sync adds an attribute, or the target profile drops a declaration — and the move would
 *                      then delete a key the administrator never saw. That key can be the condition on a
 *                      mapping rule, so the difference is not cosmetic; it is a role retracted without consent.
 *                      An empty list is the correct value for a lossless move.
 *
 *                      <p>This is a mandatory DISCLOSURE precondition, not an authorization control, and the
 *                      distinction matters: the caller has already cleared the write gate, so it can always
 *                      preview and then confirm. What it cannot do — now that the field is required rather
 *                      than optional — is skip the disclosure by omitting it and have the server delete
 *                      whatever it likes.
 */
public record SwitchProfileRequest(@NotNull UUID profileId,
                                  // Element-validated too: Set.copyOf rejects a null element with an NPE,
                                  // which surfaced as a 500 rather than a refusal.
                                  @NotNull List<@NotBlank String> confirmedKeys) {
}
