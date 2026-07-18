package com.example.sso.branding;

import java.util.UUID;

/**
 * Resolves the {@link Branding} to render for an org — its own row, else the platform override, else the
 * built-in default. The module's public entry point for surfaces that render branding (e.g. the server-rendered
 * OIDC consent page); the admin CRUD stays module-internal. Callers pass the org whose screens they render, and
 * must be inside that org's context (RLS) — the same requirement as the public read endpoint.
 */
public interface BrandingResolver {

    Branding resolve(UUID orgId);
}
