package com.example.sso.mfa.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.mfa.internal.domain.MfaFactor;
import com.example.sso.mfa.internal.domain.MfaFactorRepository;
import com.example.sso.mfa.internal.domain.MfaType;
import com.example.sso.user.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MfaServiceImpl}. The security-critical interaction is that the shared secret is
 * ENCRYPTED before it is persisted on enrollment and DECRYPTED (never read raw) on verify — asserted
 * with {@code verify} on the cipher and the repository. Replay protection is asserted on the outcome.
 */
@ExtendWith(MockitoExtension.class)
class MfaServiceImplTest {

    @Mock
    private MfaFactorRepository factors;
    @Mock
    private TotpService totpService;
    @Mock
    private SecretCipher secretCipher;

    private MfaServiceImpl service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MfaServiceImpl(factors, totpService, secretCipher, "MiniSSO");
    }

    @Test
    void confirmEnrollmentEncryptsTheSecretBeforePersistingAnEnabledFactor() {
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(totpService.matchingCounter("SECRET", "123456")).thenReturn(5L);
        when(factors.findByUserIdAndType(userId, MfaType.TOTP)).thenReturn(Optional.empty());
        when(secretCipher.encrypt("SECRET")).thenReturn("encg:cipher");

        boolean confirmed = service.confirmEnrollment(user, "SECRET", "123456");

        assertThat(confirmed).isTrue();
        verify(secretCipher).encrypt("SECRET");
        ArgumentCaptor<MfaFactor> saved = ArgumentCaptor.forClass(MfaFactor.class);
        verify(factors).save(saved.capture());
        assertThat(saved.getValue().getSecret()).isEqualTo("encg:cipher");
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getLastUsedStep()).isEqualTo(5L);
    }

    @Test
    void confirmEnrollmentRejectsAnInvalidCodeWithoutPersisting() {
        UserAccount user = mock(UserAccount.class);
        when(totpService.matchingCounter("SECRET", "000000")).thenReturn(-1L);

        assertThat(service.confirmEnrollment(user, "SECRET", "000000")).isFalse();
        verify(factors, never()).save(any());
    }

    @Test
    void confirmEnrollmentRejectsANullSecretWithoutTouchingCollaborators() {
        UserAccount user = mock(UserAccount.class);

        assertThat(service.confirmEnrollment(user, null, "123456")).isFalse();
        verifyNoInteractions(totpService, factors, secretCipher);
    }

    @Test
    void verifyTotpDecryptsTheStoredSecretAndAcceptsAFreshCode() {
        MfaFactor factor = enabledFactor("encg:stored", null);
        when(factors.findByUserIdAndType(userId, MfaType.TOTP)).thenReturn(Optional.of(factor));
        when(secretCipher.decrypt("encg:stored")).thenReturn("PLAIN");
        when(totpService.matchingCounter("PLAIN", "111111")).thenReturn(10L);

        boolean verified = service.verifyTotp(userId, "111111");

        assertThat(verified).isTrue();
        verify(secretCipher).decrypt("encg:stored");
        verify(factors).save(factor);
        assertThat(factor.getLastUsedStep()).isEqualTo(10L);
    }

    @Test
    void verifyTotpRejectsAReplayedOrOlderStep() {
        MfaFactor factor = enabledFactor("encg:stored", 20L);
        when(factors.findByUserIdAndType(userId, MfaType.TOTP)).thenReturn(Optional.of(factor));
        when(secretCipher.decrypt("encg:stored")).thenReturn("PLAIN");
        when(totpService.matchingCounter("PLAIN", "111111")).thenReturn(15L);

        assertThat(service.verifyTotp(userId, "111111")).isFalse();
        verify(factors, never()).save(any());
    }

    @Test
    void verifyTotpFailsClosedWhenNoFactorIsEnrolled() {
        when(factors.findByUserIdAndType(userId, MfaType.TOTP)).thenReturn(Optional.empty());

        assertThat(service.verifyTotp(userId, "111111")).isFalse();
        verifyNoInteractions(secretCipher);
    }

    private MfaFactor enabledFactor(String secretCipherText, Long lastUsedStep) {
        MfaFactor factor = new MfaFactor(userId, MfaType.TOTP, "Authenticator app");
        factor.assignSecret(secretCipherText);
        if (lastUsedStep != null) {
            factor.recordUsedStep(lastUsedStep);
        }
        factor.enable();
        return factor;
    }
}
