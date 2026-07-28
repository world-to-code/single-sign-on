package com.example.sso.admin.internal.user.api;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * The profile to move a user onto, and the cost the administrator was shown when they confirmed.
 *
 * <p>Deliberately a body, not a query parameter: this write deletes data.
 *
 * @param profileId    the target profile
 * @param confirmedKeys the attributes the preview said would be deleted, echoed back. Optional — a client that
 *                      omits it accepts whatever the move computes — but the console sends it, because the two
 *                      requests are independent and the answer can change between them: a directory sync can
 *                      add an attribute, or the target profile can drop a declaration, and the move would then
 *                      delete a key the administrator never saw. That key can be the condition on a mapping
 *                      rule, so the difference is not cosmetic — it is a role retracted without consent.
 */
public record SwitchProfileRequest(@NotNull UUID profileId, List<String> confirmedKeys) {
}
