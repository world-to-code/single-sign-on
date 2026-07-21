package com.example.sso.admin.internal.shared.application;

import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Which tier the administrator making this request acts in.
 *
 * <p>Small, and load-bearing anyway: callers branch on {@code administersWholeTier()} to decide between an
 * organization-wide read and a resource-subtree one. Reading it as an AND rather than an OR, or inverting it,
 * silently hands a subtree delegate the whole tenant — so the truth table is pinned here rather than left to
 * whichever caller happens to be exercised.
 */
@ExtendWith(MockitoExtension.class)
class ActingAdminTierTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock private AdminAccessPolicy accessPolicy;
    @Mock private OrgContext orgContext;

    private ActingAdminTier tier() {
        return new ActingAdminTier(accessPolicy, orgContext);
    }

    private boolean administersWholeTier(boolean unscoped, boolean administersBoundOrg) {
        lenient().when(accessPolicy.isCurrentActorUnscoped()).thenReturn(unscoped);
        lenient().when(accessPolicy.administersBoundOrg()).thenReturn(administersBoundOrg);
        return tier().administersWholeTier();
    }

    @Test
    void aWriteIsStampedWithTheOrganizationTheAdministratorIsDrilledInto() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThat(tier().actingOrg()).isEqualTo(ORG);
    }

    /**
     * Null means the PLATFORM tier genuinely, not "we could not tell": every caller runs behind
     * {@code /api/admin/**}, which already required an authenticated, MFA-complete admin to get here.
     */
    @Test
    void anUndrilledSuperAdminStampsNoOrganizationAtAll() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThat(tier().actingOrg()).isNull();
    }

    @Test
    void aPlatformSuperAdminAdministersAWholeTier() {
        assertThat(administersWholeTier(true, false)).isTrue();
    }

    @Test
    void aTenantAdminAdministersAWholeTier() {
        assertThat(administersWholeTier(false, true)).isTrue();
    }

    /**
     * The case the OR exists for. A resource-subtree delegate reaches a set of resources, never an
     * organization — answering true here is org-wide reach handed to someone scoped below it.
     */
    @Test
    void aResourceSubtreeDelegateAdministersNoTier() {
        assertThat(administersWholeTier(false, false)).isFalse();
    }

    /** Either predicate alone suffices, so holding both is still a tier — not an AND. */
    @Test
    void holdingBothStandingsIsStillATier() {
        assertThat(administersWholeTier(true, true)).isTrue();
    }
}
