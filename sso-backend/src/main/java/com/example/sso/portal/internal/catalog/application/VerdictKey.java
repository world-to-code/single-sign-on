package com.example.sso.portal.internal.catalog.application;

import java.util.Set;
import java.util.UUID;

/**
 * What a cached attribute-source verdict is about.
 *
 * <p>The organization is part of the key because attribute keys are tenant-chosen names — two tenants naming
 * a key {@code clearance} is the expected case, not an edge one — so caching on the keys alone would let one
 * tenant's connector decide another tenant's policy.
 *
 * @param orgId         null when the caller is bound to no organization: its own bucket, never shared with one
 * @param conditionKeys order-independent, since order is not part of the question
 */
record VerdictKey(UUID orgId, Set<String> conditionKeys) {
}
