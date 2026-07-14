package com.example.sso.portal.internal.catalog.application;

/**
 * Which policy field (and its tie-break priority) a {@code policy_binding} upsert writes — the AUTH
 * ({@code auth_policy_id}/{@code priority}) or the SESSION ({@code session_policy_id}/{@code session_priority})
 * axis. A co-located binding on the OTHER axis of the same row is preserved untouched.
 */
enum PolicyAxis {
    AUTH,
    SESSION
}
