package com.example.sso.metadata;

import java.util.UUID;

/**
 * Something changed about WHO can fill an organization's attributes: a connector was saved or deleted, a
 * mapping was aimed or removed, or an attribute definition changed hands between an administrator and a source.
 *
 * <p>Exists so that answer can be cached. It gates whether an attribute-conditioned policy binding applies, and
 * a cached verdict that outlives the change is a revocation that did not propagate — an administrator who
 * deletes a compromised connector would otherwise watch the binding it fed keep selecting a policy until the
 * entry expired. One event for all three inputs, because every consumer of them asks the same question.
 *
 * @param orgId the organization whose configuration changed; null for the platform tier
 */
public record AttributeSourceConfigurationChangedEvent(UUID orgId) {
}
