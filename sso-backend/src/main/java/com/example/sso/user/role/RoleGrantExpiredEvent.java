package com.example.sso.user.role;

import java.util.UUID;

/**
 * A time-bounded role grant reached its expiry and was taken away.
 *
 * <p>An event rather than a direct call to the audit module: {@code audit} already depends on {@code user} to
 * resolve who an actor is, so calling it from here would close a cycle. It is also the truer shape — nobody
 * decided this today, the clock did, and the modules that care (the audit trail now, a notification later)
 * subscribe rather than being invoked.
 *
 * @param username the holder, whose sessions are separately ended through {@code UserAccessChangedEvent}
 * @param orgId    the tenant the grant belonged to, so the record files under it
 * @param roleId   which grant lapsed
 */
public record RoleGrantExpiredEvent(String username, UUID orgId, UUID roleId) {
}
