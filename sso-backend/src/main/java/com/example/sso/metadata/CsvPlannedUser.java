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
 * @param username   the account name, which is also how an existing user is recognised
 * @param attributes the profile's attributes this row fills
 * @param groups     groups the row asks for, already checked to exist
 */
public record CsvPlannedUser(String username, Map<String, String> attributes, List<String> groups) {
}
