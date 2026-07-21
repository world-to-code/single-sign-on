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

    private BaseUserFields() {
    }
}
