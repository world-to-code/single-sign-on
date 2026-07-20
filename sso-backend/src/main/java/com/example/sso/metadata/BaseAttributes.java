package com.example.sso.metadata;

import java.util.List;

/**
 * The attributes every person has because {@code app_user} carries them as columns.
 *
 * <p>They appear in a profile so an administrator sees the whole shape of a user in one place, but they are
 * SYNTHESISED rather than stored: creating {@code attribute_definition} rows for them would be a second copy
 * of a schema the table already defines, and the two would drift. Nothing writes them here — login, email
 * one-time codes, SCIM and the session layer all read those columns directly, which is exactly why they are
 * not editable as profile attributes.
 */
public final class BaseAttributes {

    /** In the order an administrator reads them: who the person is, then how to reach them. */
    private static final List<AttributeDefinition> DEFINITIONS = List.of(
            base("username", "Username", "The login identifier, unique within the organization."),
            base("email", "Email", "Where sign-in codes are sent; also a login identifier."),
            base("displayName", "Display name", "Shown throughout the console and the user portal."),
            base("phoneNumber", "Phone number", "Used by the SMS factor once the owner has proven it."),
            base("externalId", "External ID", "The stable identifier a directory or SCIM client provisions."));

    private static final List<String> KEYS = DEFINITIONS.stream().map(AttributeDefinition::key).toList();

    private BaseAttributes() {
    }

    public static List<AttributeDefinition> definitions() {
        return DEFINITIONS;
    }

    /** Whether {@code key} names a base attribute, which no profile may redefine or delete. */
    public static boolean contains(String key) {
        return KEYS.contains(key == null ? "" : key.trim());
    }

    private static AttributeDefinition base(String key, String displayName, String description) {
        return new AttributeDefinition(null, EntityKind.USER, key, displayName, description,
                AttributeDataType.STRING, List.of(), false, false, AttributeSource.LOCAL, -1, true);
    }
}
