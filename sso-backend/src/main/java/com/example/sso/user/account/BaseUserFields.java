package com.example.sso.user.account;

/**
 * The names of the account fields that {@code app_user} carries as columns.
 *
 * <p>Published here, next to the columns themselves, because another module surfaces them as read-only
 * profile attributes and had been spelling them as string literals. A rename would have left that copy
 * pointing at nothing, silently: the schema view would offer a key no writer recognises, and the
 * undeclared-key checks that read it would start refusing a legitimate attribute.
 *
 * <p>These are compile-time constants, so a reference inlines and no module gains a runtime dependency —
 * the same shape {@code Permissions} already relies on.
 */
public final class BaseUserFields {

    public static final String USERNAME = "username";
    public static final String EMAIL = "email";
    public static final String DISPLAY_NAME = "displayName";
    public static final String PHONE_NUMBER = "phoneNumber";
    public static final String EXTERNAL_ID = "externalId";

    /**
     * How long each of those columns actually is.
     *
     * <p>Published for the same reason the names are: a bulk writer has to refuse an over-long value where the
     * administrator can still see which row it came from, and the alternative is a second copy of these numbers
     * that drifts from the schema. {@code AppUser} declares its columns FROM these constants, so a width can
     * only change in one place.
     */
    public static final int USERNAME_MAX_LENGTH = 100;
    public static final int EMAIL_MAX_LENGTH = 320;
    public static final int DISPLAY_NAME_MAX_LENGTH = 200;

    private BaseUserFields() {
    }
}
