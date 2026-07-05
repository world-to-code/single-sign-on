package com.example.sso.scim.internal.application;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    @InjectMocks
    private ScimTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty()); // platform tier by default
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
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
}
