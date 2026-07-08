package com.example.sso.user;

import java.util.UUID;

/**
 * Published when a user's access is revoked or changed (disabled, deleted, or roles/permissions altered).
 * The session module terminates that user's live sessions in response, so a revoked or demoted principal
 * cannot keep acting on a frozen (Redis-serialized) SecurityContext until it idle/absolute-expires.
 *
 * <p>{@code orgId} is the user's owning organization (the tenant), or {@code null} for a global/platform
 * account. It scopes the termination to the sessions bound to THAT org — usernames are unique only WITHIN an
 * org, so a bare-username termination would also end a same-named user in another tenant (cross-tenant
 * over-termination). Sessions of a global user carry no org marker.
 */
public record UserAccessChangedEvent(String username, UUID orgId) {
}
