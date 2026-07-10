package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.RsaKeyService;
import com.example.sso.crypto.internal.domain.SigningKey;
import com.example.sso.crypto.internal.domain.SigningKeyRepository;
import com.example.sso.crypto.internal.domain.SigningKeyRetentionRepository;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-level proof of the JWKS retention behavior, against the real database (RLS runtime role,
 * Flyway schema): the derived finders really order ACTIVE first then rotated-away by recency, the JWKS is
 * really bounded by the tier's retention in the query, and the retention setting really resolves
 * config-default → copy-on-write org row. The unit tests pin the same logic against mocks; this pins the
 * derived-query and schema behavior the mocks presuppose.
 */
class JwksRetentionIT extends AbstractIntegrationTest {

    @Autowired
    RsaKeyService rsaKeyService;
    @Autowired
    SigningKeyRepository signingKeys;
    @Autowired
    SigningKeyRetentionRepository retentions;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private UUID orgId;

    @AfterEach
    void tearDown() {
        if (orgId != null) {
            organizations.delete(orgId); // FK ON DELETE CASCADE removes the org's keys + retention row
        }
    }

    private UUID newOrg(String slug) {
        orgId = organizations.create(new NewOrganization(slug, slug)).id();
        return orgId;
    }

    @Test
    void findersOrderActiveFirstThenNewestAndTheJwksIsBoundedByRetention() {
        UUID org = newOrg("jwks-order-it-" + UUID.randomUUID().toString().substring(0, 8));

        // Three real rotations: kid1 and kid2 are rotated away, kid3 is the active signer.
        String kid1 = orgContext.callInOrg(org, () -> rsaKeyService.rotate());
        String kid2 = orgContext.callInOrg(org, () -> rsaKeyService.rotate());
        String kid3 = orgContext.callInOrg(org, () -> rsaKeyService.rotate());

        // The derived query itself (not a mock) returns active first, then rotated-away by recency.
        List<String> ordered = orgContext.callInOrg(org,
                        () -> signingKeys.findByOrgIdOrderByActiveDescCreatedAtDesc(org, Limit.of(10)))
                .stream().map(SigningKey::getKid).toList();
        assertThat(ordered).containsExactly(kid3, kid2, kid1);

        // With retention=1 the published set is exactly [active, newest rotated-away] — bounded in the query.
        orgContext.runInOrg(org, () -> rsaKeyService.updateRetainedInactiveKeys(1));
        JWKSet jwks = orgContext.callInOrg(org, () -> rsaKeyService.buildJwkSet());
        assertThat(jwks.getKeys()).hasSize(2);
        assertThat(jwks.getKeys().get(0).getKeyID()).isEqualTo(kid3);
        assertThat(jwks.getKeys().get(1).getKeyID()).isEqualTo(kid2);
    }

    @Test
    void retentionResolvesTheConfigDefaultUntilTheOrgCopyOnWritesItsOwnRow() {
        UUID org = newOrg("jwks-retention-it-" + UUID.randomUUID().toString().substring(0, 8));

        // No row anywhere → the application.yml default (1) applies.
        assertThat(orgContext.callInOrg(org, () -> rsaKeyService.retainedInactiveKeys())).isEqualTo(1);

        // First save copy-on-writes the org's OWN row (the RLS-free table is scoped by the query).
        orgContext.runInOrg(org, () -> rsaKeyService.updateRetainedInactiveKeys(0));
        assertThat(orgContext.callInOrg(org, () -> rsaKeyService.retainedInactiveKeys())).isZero();
        assertThat(retentions.findByOrgId(org)).hasValueSatisfying(row -> {
            assertThat(row.getOrgId()).isEqualTo(org);
            assertThat(row.getRetainedInactiveKeys()).isZero();
        });
        // The global default row was never created — the org customized only its own tier.
        assertThat(retentions.findByOrgIdIsNull()).isEmpty();
    }
}
