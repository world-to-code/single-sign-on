package com.example.sso.user;

/**
 * Published when a user's access is revoked or changed (disabled, deleted, or roles/permissions altered).
 * The session module terminates that user's live sessions in response, so a revoked or demoted principal
 * cannot keep acting on a frozen (Redis-serialized) SecurityContext until it idle/absolute-expires.
 */
public record UserAccessChangedEvent(String username) {
}
