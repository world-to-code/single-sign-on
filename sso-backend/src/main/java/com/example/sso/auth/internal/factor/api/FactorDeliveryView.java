package com.example.sso.auth.internal.factor.api;

/**
 * Whether the code this session last requested failed to leave. A single flag on purpose: the reason is the
 * tenant administrator's business (it is in the audit trail), not the person waiting for a text.
 */
public record FactorDeliveryView(boolean failed) {
}
