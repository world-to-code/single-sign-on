package com.example.sso.saml.inbound;

import java.time.Instant;
import java.util.Map;

/**
 * What a fully verified assertion asserts. {@code assertionId} exists so the caller can enforce single use, and
 * {@code expiresAt} bounds how long that record must be kept — a replay after the assertion has expired is
 * refused by the validity window anyway.
 */
public record VerifiedAssertion(String assertionId, String nameId, Instant expiresAt,
                                Map<String, String> attributes) {
}
