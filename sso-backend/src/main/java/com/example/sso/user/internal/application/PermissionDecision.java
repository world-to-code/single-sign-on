package com.example.sso.user.internal.application;

/** The outcome of resolving ONE permission across the deny/allow specificity levels for a user. */
enum PermissionDecision {
    /** Held: an allow at the most specific level that spoke won, with no deny overriding it there. */
    ALLOW,
    /** Withheld: a deny at the most specific level that spoke won (or was carved out and lost — see resolver). */
    DENY,
    /** No level spoke — the permission is neither granted nor denied to this user. */
    SILENT
}
