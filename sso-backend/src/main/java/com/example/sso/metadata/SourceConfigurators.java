package com.example.sso.metadata;

import java.util.Collection;
import java.util.UUID;

/**
 * Who is accountable for one KIND of identity source.
 *
 * <p>The provenance guards ask a single question — "can the sources filling these attributes be attributed to
 * an administrator?" — but the answer is found in a different place for each kind: a directory's configurator
 * is on its connector, a SCIM client's is on the token somebody issued. Before this, {@code directory} answered
 * for all of them, which meant it silently answered "nobody" for SCIM: a source profile with no connector was
 * skipped, so every SCIM-fed attribute was permanently unattributable and could drive nothing.
 *
 * <p>Each module that owns a kind of source implements this for its own kinds. A kind NO implementation claims
 * is unattributable by construction, which is the correct answer rather than an oversight — {@code metadata}
 * treats an unclaimed source profile as incomplete, so the guards fail closed.
 */
public interface SourceConfigurators {

    /** Whether this contributor speaks for that kind of source. */
    boolean handles(ProfileKind kind);

    /**
     * The administrators accountable for the sources of this kind on the ACTING TENANT — the given profiles
     * identify which of them are in play, and every one of them is a kind this contributor {@link #handles}.
     *
     * <p>Per-tenant rather than strictly per-profile, because that is the granularity accountability actually
     * has: a SCIM token is issued to a tenant, not to a profile, and one token can write through any of them.
     * An implementation may therefore answer more broadly than the ids it was given, never more narrowly.
     *
     * <p>A source with nobody on record makes the answer incomplete rather than empty — the caller must be
     * able to tell "attributed to nobody" from "we could not tell", because only one of them is safe to act on.
     */
    AttributeSourceAuthors configuratorsOf(Collection<UUID> sourceProfileIds);
}
