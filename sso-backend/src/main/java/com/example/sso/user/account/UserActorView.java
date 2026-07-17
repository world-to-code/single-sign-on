package com.example.sso.user.account;

import java.util.UUID;

/**
 * Minimal actor identity — id/email/display — for audit attribution, resolved WITHOUT the role/permission
 * hydration the full {@link UserAccount} carries. Audit writes are a hot path that only needs to name the
 * actor, never their authorities, so this avoids the RBAC query fan-out per event.
 */
public record UserActorView(UUID id, String email, String displayName) {
}
