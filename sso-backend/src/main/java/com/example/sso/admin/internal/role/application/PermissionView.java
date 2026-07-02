package com.example.sso.admin.internal.role.application;

/**
 * Structured catalog entry for a permission, split into its {@code resource:action} parts so the admin
 * UI can group permissions by resource when composing roles.
 */
public record PermissionView(String name, String resource, String action) {

    /** Splits a {@code resource:action} permission name; single-token names map to a blank action. */
    public static PermissionView of(String name) {
        int sep = name.indexOf(':');
        return sep < 0
                ? new PermissionView(name, name, "")
                : new PermissionView(name, name.substring(0, sep), name.substring(sep + 1));
    }
}
