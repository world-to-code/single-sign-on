package com.example.sso.metadata;

import java.util.Collection;

/**
 * Answers "who can fill these attributes?" for the acting tenant.
 *
 * <p>Lives here rather than in either caller because two of them need it — auto-mapping, before letting an
 * attribute grant a role, and policy binding, before letting one select a policy — and the question is about
 * attribute provenance, which is this module's subject. The implementation lives in {@code directory}, which
 * owns connectors; both callers already depend on {@code metadata}, so nobody gains an edge.
 */
public interface AttributeSourceAuthority {

    /** The configurators behind every source that can fill any of {@code attrKeys} on the tenant's profile. */
    AttributeSourceAuthors authorsFilling(Collection<String> attrKeys);
}
