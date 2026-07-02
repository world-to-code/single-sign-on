package com.example.sso.scim.internal.application;

import com.example.sso.scim.internal.domain.ScimToken;
import com.example.sso.scim.internal.domain.ScimTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @InjectMocks
    private ScimTokenServiceImpl service;

    @Test
    void issueReturnsTheRawTokenAndPersistsAHashedRecord() {
        String raw = service.issue("ci-agent", Duration.ofHours(1));

        assertThat(raw).isNotBlank();
        verify(tokens).save(any(ScimToken.class));
    }

    @Test
    void isValidIsTrueForAnActiveStoredToken() {
        ScimToken token = mock(ScimToken.class);
        when(token.isActiveAt(any(Instant.class))).thenReturn(true);
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThat(service.isValid("raw-token")).isTrue();
    }

    @Test
    void isValidIsFalseForAnUnknownToken() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.isValid("raw-token")).isFalse();
    }
}
