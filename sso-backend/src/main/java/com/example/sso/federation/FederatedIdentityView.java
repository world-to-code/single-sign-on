package com.example.sso.federation;

import java.time.Instant;
import java.util.UUID;

/**
 * One upstream identity bound to a local account, as an administrator sees it. {@code subjectHint} is a
 * FINGERPRINT of the upstream {@code sub} — a truncated digest, never a prefix. It does what an admin needs
 * (tell two identities apart, match one against the upstream's console) without handing back the identifier
 * itself, which a prefix would leak, and would reveal entirely for a short subject.
 */
public record FederatedIdentityView(UUID id, String providerAlias, String issuer, String subjectHint,
                                    Instant linkedAt) {
}
