package com.example.sso.scim.internal.application;

import com.example.sso.metadata.AttributeValueGrantGuard;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMapping;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.shared.error.ForbiddenException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.example.sso.scim.internal.domain.ScimToken;
import com.example.sso.scim.internal.domain.ScimTokenRepository;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScimTokenServiceImpl}: issuing persists a hashed token (never the raw value)
 * and returns the raw once; validity defers to the stored token's activeness. Issuance is an
 * interaction (save), asserted with {@code verify}; validity is asserted on the outcome.
 */
@ExtendWith(MockitoExtension.class)
class ScimTokenServiceImplTest {

    @Mock
    private ScimTokenRepository tokens;
    @Mock
    private OrgContext orgContext;
    @Mock
    private ProfileService profiles;
    @Mock
    private ProfileMappingService mappings;
    @Mock
    private AttributeValueGrantGuard grantGuard;

    @InjectMocks
    private ScimTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty()); // platform tier by default
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        lenient().when(profiles.list()).thenReturn(List.of());
        lenient().when(grantGuard.keysBeyondAuthority(any())).thenReturn(Set.of());
    }

    @Test
    void issueReturnsTheRawTokenAndPersistsAHashedRecord() {
        String raw = service.issue("ci-agent", Duration.ofHours(1));

        assertThat(raw).isNotBlank();
        verify(tokens).save(any(ScimToken.class));
    }

    @Test
    void authenticateReturnsThePrincipalForAnActiveToken() {
        ScimToken token = mock(ScimToken.class);
        when(token.isActiveAt(any(Instant.class))).thenReturn(true);
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        // Resolved cross-org (callAsPlatform) so an org-owned token isn't hidden before the request is bound.
        assertThat(service.authenticate("raw-token")).isPresent();
    }

    @Test
    void authenticateIsEmptyForAnUnknownToken() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.authenticate("raw-token")).isEmpty();
    }

    /**
     * A SCIM token licenses writes to every attribute its profile maps, so if one of those decides a grant a
     * mapping rule confers, issuing the token would hand that grant to whoever the token provisions. Refused
     * at issue, where the failure is a token that is never minted.
     */
    @Test
    void issuingIsRefusedWhenTheSourceLicensesAKeyBeyondTheIssuersAuthority() {
        UUID scimId = UUID.randomUUID();
        Profile scim = new Profile(scimId, "SCIM", ProfileKind.SCIM, null, false, false);
        when(profiles.list()).thenReturn(List.of(scim));
        when(mappings.mappingsFrom(scimId)).thenReturn(List.of(
                new ProfileMapping(UUID.randomUUID(), scimId, "clearance", UUID.randomUUID(), "clearance")));
        when(grantGuard.keysBeyondAuthority(Set.of("clearance"))).thenReturn(Set.of("clearance"));

        assertThatThrownBy(() -> service.issue("agent", Duration.ofHours(1)))
                .isInstanceOf(ForbiddenException.class);
        verify(tokens, never()).save(any());
    }
}
