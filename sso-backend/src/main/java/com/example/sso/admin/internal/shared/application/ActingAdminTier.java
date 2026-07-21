package com.example.sso.admin.internal.shared.application;

import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Which tier the administrator making this request acts in.
 *
 * <p>Two questions that always travel together and were answered privately in several services: the
 * organization a write should be stamped with, and whether the actor administers a whole tier at all rather
 * than a resource subtree. Keeping them in one place is what stops one caller scoping a read by the acting org
 * while another forgets to.
 */
@Component
@RequiredArgsConstructor
public class ActingAdminTier {

    private final AdminAccessPolicy accessPolicy;
    private final OrgContext orgContext;

    /**
     * The organization to stamp on a write, or null for the platform tier.
     *
     * <p>Null means the platform genuinely — an un-drilled super admin — not "we could not tell": every caller
     * here runs behind {@code /api/admin/**}, which already requires an authenticated, MFA-complete admin.
     */
    public UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    /**
     * A platform super admin (drilled or not) OR a tenant admin — both scope to their acting tier. Everyone
     * else is a resource-subtree delegate, whose reach is a set of resources rather than an organization.
     */
    public boolean administersWholeTier() {
        return accessPolicy.isCurrentActorUnscoped() || accessPolicy.administersBoundOrg();
    }
}
