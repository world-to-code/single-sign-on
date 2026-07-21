package com.example.sso.metadata;

import java.util.Collection;

/**
 * Answers "who can fill these attributes?" for the acting tenant.
 *
 * <p>Lives here rather than in either caller because two of them need it — auto-mapping, before letting an
 * attribute grant a role, and policy binding, before letting one select a policy — and the question is about
 * attribute provenance, which is this module's subject. The implementation is here too, because no other
 * module can answer for every kind of source: it finds which sources fill the attributes and asks each one's
 * owning module through {@link SourceConfigurators}.
 */
public interface AttributeSourceAuthority {

    /** The configurators behind every source that can fill any of {@code attrKeys} on the tenant's profile. */
    AttributeSourceAuthors authorsFilling(Collection<String> attrKeys);
}
