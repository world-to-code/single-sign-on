package com.example.sso.user.internal.account.domain;

import java.util.UUID;

/**
 * One account's directory identifier paired with its id — the whole of what correlating a directory entry to a
 * local account needs. Deliberately NOT a {@code UserAccount}: a sync reads no roles or permissions, and
 * hydrating an RBAC graph per entry turned one page of a directory into thousands of round trips.
 */
public record ExternalIdRow(String externalId, UUID userId) {
}
