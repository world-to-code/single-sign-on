package com.example.sso.email.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.email.internal.domain.SmtpSettings;
import com.example.sso.email.internal.domain.SmtpSettingsRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SmtpSettingsService}: own-else-global resolution for the sender, the password encrypted
 * before persist and NEVER surfaced in the view, the fail-closed platform-write guard, and the SSRF/port/TLS
 * validation refusing (and not persisting) a bad relay.
 */
@ExtendWith(MockitoExtension.class)
class SmtpSettingsServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    SmtpSettingsRepository repository;
    @Mock
    SecretCipher cipher;
    @Mock
    OutboundHostValidator hostValidator;
    @Mock
    OrgContext orgContext;

    private SmtpSettingsService service;

    @BeforeEach
    void setUp() {
        service = new SmtpSettingsService(repository, cipher, hostValidator, orgContext);
    }

    private SmtpSettings row(UUID orgId, String encryptedPassword) {
        return SmtpSettings.create(orgId, "smtp.acme.example", 587, "postmaster", encryptedPassword,
                "no-reply@acme.example", true);
    }

    @Test
    void resolveReturnsTheOrgsOwnRowWithADecryptedPassword() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG, "encg:cipher")));
        when(cipher.decrypt("encg:cipher")).thenReturn("s3cret");

        MailServer server = service.resolve(ORG).orElseThrow();

        assertThat(server.host()).isEqualTo("smtp.acme.example");
        assertThat(server.password()).isEqualTo("s3cret"); // decrypted only here, for the sender
        assertThat(server.authenticated()).isTrue();
    }

    @Test
    void resolveFallsBackToThePlatformOverrideThenEmpty() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null, null)));
        assertThat(service.resolve(ORG)).isPresent(); // inherits the platform override

        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());
        assertThat(service.resolve(ORG)).isEmpty(); // no row anywhere → caller uses application.yml default
    }

    @Test
    void aNullOrgResolvesOnlyThePlatformOverride() {
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null, null)));
        assertThat(service.resolve(null)).isPresent();
        verify(repository, never()).findByOrgId(any());
    }

    @Test
    void theViewNeverCarriesThePassword() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG, "encg:cipher")));

        SmtpSettingsView view = service.get();

        assertThat(view.configured()).isTrue();
        assertThat(view.host()).isEqualTo("smtp.acme.example");
        // SmtpSettingsView is a record with no password component at all — it cannot leak the secret.
        assertThat(view.toString()).doesNotContain("cipher").doesNotContain("s3cret");
    }

    @Test
    void getIsNotConfiguredWhenTheTierHasNoOwnRow() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        assertThat(service.get().configured()).isFalse();
    }

    @Test
    void updateEncryptsThePasswordAndPersistsToTheActingTenant() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(cipher.encrypt("s3cret")).thenReturn("encg:cipher");

        service.update(new SmtpSettingsSpec("smtp.acme.example", 587, "postmaster", "s3cret",
                "no-reply@acme.example", true));

        ArgumentCaptor<SmtpSettings> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getPasswordEncrypted()).isEqualTo("encg:cipher"); // ciphertext, never plaintext
        verify(hostValidator).validate("smtp.acme.example");
    }

    @Test
    void updateRejectsAnSsrfHostAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("127.0.0.1");

        assertThatThrownBy(() -> service.update(
                new SmtpSettingsSpec("127.0.0.1", 587, "u", "p", null, true)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsANonAllowlistedPortAndPlaintextRelay() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        assertThatThrownBy(() -> service.update(new SmtpSettingsSpec("smtp.acme.example", 8080, "u", "p", null, true)))
                .isInstanceOf(BadRequestException.class); // port not allowlisted
        assertThatThrownBy(() -> service.update(new SmtpSettingsSpec("smtp.acme.example", 587, "u", "p", null, false)))
                .isInstanceOf(BadRequestException.class); // no TLS on a non-465 port
        verify(repository, never()).save(any());
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false); // bound to a tier but no org resolved → fail closed

        assertThatThrownBy(() -> service.update(new SmtpSettingsSpec("smtp.acme.example", 587, "u", "p", null, true)))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void getDoesNotSurfaceTheGlobalRowToABoundOrglessNonPlatformCaller() {
        // The read path must be symmetric with the write guard: an orgless non-platform caller owns nothing and
        // must NOT see the platform-global relay's config as if it were its own (a cross-tier leak).
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThat(service.get().configured()).isFalse();
        verify(repository, never()).findByOrgIdIsNull(); // the global row is never resolved as "own" here
    }

    @Test
    void anImplicitTls465RelayIsAcceptedWithoutStarttls() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(cipher.encrypt("s3cret")).thenReturn("encg:cipher");

        service.update(new SmtpSettingsSpec("smtp.acme.example", 465, "postmaster", "s3cret", null, false));

        verify(repository).save(any()); // 465 is TLS-implicit, so starttls=false is allowed
    }

    @Test
    void updatingAnExistingRowReconfiguresItInPlaceRatherThanInserting() {
        SmtpSettings existing = row(ORG, "encg:old");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));
        when(cipher.encrypt("new-secret")).thenReturn("encg:new");

        service.update(new SmtpSettingsSpec("smtp.new.example", 587, "postmaster", "new-secret", null, true));

        verify(repository, never()).save(any()); // mutated in place (dirty-checked), no second row inserted
        assertThat(existing.getHost()).isEqualTo("smtp.new.example");
        assertThat(existing.getPasswordEncrypted()).isEqualTo("encg:new");
    }

    @Test
    void updateWithoutAPasswordKeepsTheStoredSecret() {
        SmtpSettings existing = row(ORG, "encg:kept");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        // The view never returns the password, so an edit of other fields submits a blank one — it must NOT wipe.
        service.update(new SmtpSettingsSpec("smtp.new.example", 587, "postmaster", null, null, true));

        assertThat(existing.getHost()).isEqualTo("smtp.new.example");
        assertThat(existing.getPasswordEncrypted()).isEqualTo("encg:kept"); // retained, not cleared
        verify(cipher, never()).encrypt(any());
    }

    @Test
    void updateToAnUnauthenticatedRelayClearsTheStoredPassword() {
        SmtpSettings existing = row(ORG, "encg:kept");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        service.update(new SmtpSettingsSpec("smtp.new.example", 25, null, null, null, true));

        assertThat(existing.getUsername()).isNull();
        assertThat(existing.getPasswordEncrypted()).isNull(); // no auth → no stored secret
    }

    @Test
    void deleteRemovesTheActingTiersOwnRow() {
        SmtpSettings existing = row(ORG, "encg:cipher");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        service.delete();

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsANoOpWhenTheTierHasNoOwnRow() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());

        service.delete();

        verify(repository, never()).delete(any());
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotDeleteTheGlobalDefault() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service.delete()).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }
}
