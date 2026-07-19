package com.example.sso.federation;

import java.time.Instant;
import java.util.UUID;

/**
 * One upstream identity bound to a local account, as an administrator sees it. {@code subjectHint} is a short
 * prefix of the upstream {@code sub}, not the whole value: an admin needs to tell two identities apart and to
 * correlate one with their IdP, which a prefix does, while the full opaque identifier is credential-shaped
 * material that does not need to be readable on a management screen.
 */
public record FederatedIdentityView(UUID id, String providerAlias, String issuer, String subjectHint,
                                    Instant linkedAt) {
}
