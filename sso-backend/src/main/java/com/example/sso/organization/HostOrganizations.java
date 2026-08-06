package com.example.sso.organization;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a request HOST to the organization (the tenant) it addresses and may be served for.
 *
 * <p>This rule decides which tenant an unauthenticated caller is talking to, so it has to say the same thing
 * everywhere it is asked. It used to be written twice — once in {@code security.HostOrgResolver} and once
 * privately inside the branding controller, which could not import the first without closing a module cycle
 * (branding→security→…→oidc→branding). The second copy documented that it "mirrors" the first, which is a
 * promise to keep two security-relevant rules in step by hand.
 *
 * <p>So the rule moved DOWN instead of sideways: it lives in the module that owns slug→organization, which
 * both callers already depend on, and neither has to reach across to the other.
 */
public interface HostOrganizations {

    /**
     * The ACTIVE organization this host addresses.
     *
     * <p>Empty covers every way a host can fail to name a servable tenant — the apex or a foreign host, a slug
     * that names no organization, and one whose organization is suspended. The caller cannot tell those apart,
     * which is deliberate: distinguishing them would answer "does this tenant exist" to anyone who asks.
     */
    Optional<UUID> activeOrgForHost(String host);
}
