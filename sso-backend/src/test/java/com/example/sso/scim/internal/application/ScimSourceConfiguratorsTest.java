package com.example.sso.scim.internal.application;

import com.example.sso.metadata.AttributeSourceAuthors;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.scim.internal.domain.ScimToken;
import com.example.sso.scim.internal.domain.ScimTokenRepository;
import com.example.sso.tenancy.OrgContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A SCIM client has no connector, so before this there was nowhere to record who aimed it — and the guards,
 * which refuse an attribute nobody is accountable for, refused every SCIM-fed attribute forever. The token is
 * the artefact that carries the accountability: issuing one hands out the ability to write this tenant's
 * attributes.
 */
@ExtendWith(MockitoExtension.class)
class ScimSourceConfiguratorsTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID ADA = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Mock private ScimTokenRepository tokens;
    @Mock private OrgContext orgContext;

    private ScimSourceConfigurators configurators;

    @BeforeEach
    void setUp() {
        configurators = new ScimSourceConfigurators(tokens, orgContext, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
    }

    private ScimToken token(UUID issuedBy, Instant expiresAt) {
        return new ScimToken("ci", "hash-" + UUID.randomUUID(), expiresAt, ORG, issuedBy);
    }

    /** No org: enters the platform context and can provision into ANY tenant. */
    private ScimToken globalToken(UUID issuedBy) {
        return new ScimToken("seed", "hash-" + UUID.randomUUID(), null, null, issuedBy);
    }

    @Test
    void itSpeaksForScimAndNothingElse() {
        assertThat(configurators.handles(ProfileKind.SCIM)).isTrue();
        assertThat(configurators.handles(ProfileKind.LDAP)).isFalse();
        assertThat(configurators.handles(ProfileKind.TENANT)).isFalse();
    }

    /** The whole point: a SCIM source can now be attributed, so its attributes can drive a decision. */
    @Test
    void theIssuerOfALiveTokenIsAccountable() {
        when(tokens.findByOrgIdOrOrgIdIsNull(ORG)).thenReturn(List.of(token(ADA, NOW.plusSeconds(3600))));

        AttributeSourceAuthors authors = configurators.configuratorsOf(Set.of(PROFILE));

        assertThat(authors.fullyAttributed()).isTrue();
        assertThat(authors.configurators()).containsExactly(ADA);
    }

    /**
     * Every live token counts, not the one that happened to write the value: the guards run when the attribute
     * is EVALUATED, long after the client that wrote it is gone. One unattributed token is enough to make the
     * whole answer unusable, because any of them could have been the writer.
     */
    @Test
    void oneUnattributedTokenPoisonsTheAnswer() {
        when(tokens.findByOrgIdOrOrgIdIsNull(ORG)).thenReturn(List.of(
                token(ADA, null), token(null, null)));

        AttributeSourceAuthors authors = configurators.configuratorsOf(Set.of(PROFILE));

        assertThat(authors.complete()).isFalse();
        assertThat(authors.fullyAttributed()).isFalse();
    }

    /** An expired token cannot be writing anything, so it neither vouches nor poisons. */
    @Test
    void anExpiredTokenIsNotConsidered() {
        when(tokens.findByOrgIdOrOrgIdIsNull(ORG)).thenReturn(List.of(
                token(ADA, NOW.plusSeconds(60)), token(null, NOW.minusSeconds(60))));

        AttributeSourceAuthors authors = configurators.configuratorsOf(Set.of(PROFILE));

        assertThat(authors.fullyAttributed()).isTrue();
        assertThat(authors.configurators()).containsExactly(ADA);
    }

    /** No live token means nothing is writing through SCIM, so there is nothing to vouch for. */
    @Test
    void noLiveTokenLeavesNobodyToVouch() {
        when(tokens.findByOrgIdOrOrgIdIsNull(ORG)).thenReturn(List.of());

        assertThat(configurators.configuratorsOf(Set.of(PROFILE)).fullyAttributed()).isFalse();
    }

    /** Unbound means we could not look, which is not the same as finding nobody — and must not read as one. */
    @Test
    void noOrganizationBoundMakesTheAnswerIncompleteRatherThanEmpty() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());

        assertThat(configurators.configuratorsOf(Set.of(PROFILE)).complete()).isFalse();
    }
    /**
     * A platform-global token is the easiest writer to overlook: it has no org, so a query for the tenant's
     * own tokens does not return it, yet it enters the platform context and can provision into any tenant.
     * Reporting the tenant as fully accountable while one of those is live and unattributed would have the
     * guard pass on a record it has no right to trust.
     */
    @Test
    void anUnattributedGlobalTokenPoisonsEveryTenantsAnswer() {
        when(tokens.findByOrgIdOrOrgIdIsNull(ORG)).thenReturn(List.of(
                token(ADA, null), globalToken(null)));

        assertThat(configurators.configuratorsOf(Set.of(PROFILE)).complete()).isFalse();
    }
}
