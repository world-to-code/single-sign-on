package com.example.sso.user.internal.account.domain;

import java.util.UUID;

/**
 * One account's directory identifier paired with its id — the whole of what correlating a directory entry to a
 * local account needs. Deliberately NOT a {@code UserAccount}: a sync reads no roles or permissions, and
 * hydrating an RBAC graph per entry turned one page of a directory into thousands of round trips.
 *
 * <p>An interface projection rather than a record, so the query selects aliased columns and needs no JPQL
 * constructor expression — which would have to name this type fully-qualified inside the query string.
 */
public interface ExternalIdRow {

    String getExternalId();

    UUID getUserId();
}
