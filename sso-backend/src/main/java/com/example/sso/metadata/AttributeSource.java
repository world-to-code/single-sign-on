package com.example.sso.metadata;

/**
 * Who owns an attribute's value.
 *
 * <p>The distinction exists to make one failure mode impossible: an administrator edits a value, a directory
 * sync runs hours later, and the edit silently disappears. A {@link #DIRECTORY} attribute is read-only in the
 * console and a sync overwrites it; a {@link #LOCAL} attribute is the administrator's and no sync touches it.
 * An attribute that needs manual entry is simply defined as LOCAL.
 */
public enum AttributeSource {

    /** An administrator owns the value; directory syncs leave it alone. */
    LOCAL,
    /** A directory connector owns the value; the console shows it read-only. */
    DIRECTORY
}
