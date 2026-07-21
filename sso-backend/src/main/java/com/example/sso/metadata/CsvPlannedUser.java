package com.example.sso.metadata;

import java.util.List;
import java.util.Map;

/**
 * A user an import would create, as the preview describes them.
 *
 * <p>Nothing here has been written. The point of the type is that an administrator sees the accounts a file
 * would produce BEFORE it produces them — a bulk create is the one import path that makes accounts rather
 * than filling existing ones, so a file aimed wrongly must not be able to do it quietly.
 *
 * @param line       the line in the uploaded file this row came from, carried so a failure DURING the apply —
 *                   a race with another administrator, a group renamed since the preview — can name the row
 *                   the way a failure during planning does
 * @param username   the account name, which is also how an existing user is recognised
 * @param base       values that become COLUMNS of the account (email, displayName) rather than attributes.
 *                   Kept apart because the profile validator refuses these keys by name — they are app_user's,
 *                   not entity_attribute's, and handing it the whole map failed every row of every real import
 * @param attributes the profile's own attributes this row fills
 * @param groups     groups the row asks for, already checked to exist
 */
public record CsvPlannedUser(long line, String username, Map<String, String> base,
                             Map<String, String> attributes, List<String> groups) {
}
