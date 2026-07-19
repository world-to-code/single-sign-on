package com.example.sso.federation.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.federation.IdentityProviderSpec;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.federation.internal.domain.IdentityProvider;
import com.example.sso.federation.internal.domain.IdentityProviderRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserService;
import org.springframework.context.ApplicationEventPublisher;
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
 * Unit tests for {@link IdentityProviderServiceImpl}: the client secret encrypted before persist and NEVER in a
 * view, the scope list forced to include {@code openid}, alias/issuer/SSRF validation refusing (and not
 * persisting) a bad provider, upsert-in-place with a blank secret retained, and the fail-closed read/write
 * guard denying a bound-but-orgless non-platform caller. Mirrors {@code SmtpSettingsServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class IdentityProviderServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ALIAS = "google";
    private static final String ISSUER = "https://accounts.google.com";

    @Mock
    IdentityProviderRepository repository;
    @Mock
    SecretCipher cipher;
    @Mock
    FederatedIdentityLinkStore links;
    @Mock
    UserService users;
    @Mock
    ApplicationEventPublisher events;
    @Mock
    OutboundHostValidator hostValidator;
    @Mock
    OrgContext orgContext;

    private IdentityProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IdentityProviderServiceImpl(repository, cipher, links, users, events, hostValidator,
                orgContext);
    }

    private IdentityProvider row(UUID orgId, String encryptedSecret) {
        return IdentityProvider.create(orgId, ALIAS, "Google", ISSUER, "client-123", encryptedSecret,
                "openid email profile", true, false, true);
    }

    private IdentityProviderSpec spec(String secret, String scopes) {
        // Three adjacent booleans, all DIFFERENT: two sharing a value makes a swap between them invisible.
        return new IdentityProviderSpec(ALIAS, "Google", ISSUER, "client-123", secret, scopes, true, false, true);
    }

    @Test
    void saveEncryptsTheSecretAndPersistsToTheActingTenant() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt("s3cret")).thenReturn("encg:cipher");

        service.save(spec("s3cret", "email profile"));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getClientSecretEncrypted()).isEqualTo("encg:cipher"); // ciphertext, never plaintext
        // openid is INJECTED at the front and the caller's scopes preserved (not dropped/reordered/duplicated).
        assertThat(saved.getValue().getScopes()).isEqualTo("openid email profile");
        verify(hostValidator).validate("accounts.google.com"); // SSRF check on the issuer host
    }

    @Test
    void savePersistsBothBooleansDistinctlyAndDoesNotSwapThem() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn("encg:cipher");

        // Asymmetric values catch a swap of the two adjacent booleans anywhere in spec→create→entity→view.
        service.save(new IdentityProviderSpec(ALIAS, "Google", ISSUER, "client-123", "s", "openid", false, false, true));

        ArgumentCaptor<IdentityProvider> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isAllowJitProvisioning()).isFalse();
        assertThat(saved.getValue().isEnabled()).isTrue();
    }

    @Test
    void theViewCarriesEveryFieldExceptTheSecretWithBooleansUnswapped() {
        // Asymmetric booleans (allowJit=false, enabled=true) catch a swap in toView's two adjacent boolean args.
        IdentityProvider stored = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123", "encg:cipher",
                "openid email", false, true, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(stored));

        IdentityProviderView view = service.get(ALIAS);

        assertThat(view.alias()).isEqualTo(ALIAS);
        assertThat(view.displayName()).isEqualTo("Google");
        assertThat(view.issuerUri()).isEqualTo(ISSUER);
        assertThat(view.clientId()).isEqualTo("client-123");
        assertThat(view.scopes()).isEqualTo("openid email");
        // All three adjacent booleans asserted, and the fixture gives them DIFFERENT values — two sharing one
        // would make a swap between them undetectable, which is the whole point of checking them here.
        assertThat(view.allowJitProvisioning()).isFalse();
        assertThat(view.linkByVerifiedEmail()).isTrue();
        assertThat(view.enabled()).isTrue();
        // The record has no secret component at all — it cannot leak the ciphertext or plaintext.
        assertThat(view.toString()).doesNotContain("cipher").doesNotContain("s3cret");
    }

    @Test
    void getThrowsNotFoundWhenTheAliasIsAbsent() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ALIAS)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updatingAnExistingProviderReconfiguresItInPlaceRatherThanInserting() {
        IdentityProvider existing = row(ORG, "encg:old");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(cipher.encrypt("new-secret")).thenReturn("encg:new");

        service.save(spec("new-secret", "openid"));

        verify(repository, never()).save(any()); // mutated in place (dirty-checked), no second row
        assertThat(existing.getClientSecretEncrypted()).isEqualTo("encg:new");
    }

    @Test
    void saveWithoutASecretKeepsTheStoredOne() {
        IdentityProvider existing = row(ORG, "encg:kept");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        // The view never returns the secret, so an edit of other fields submits a blank one — it must NOT wipe.
        service.save(spec("  ", "openid"));

        assertThat(existing.getClientSecretEncrypted()).isEqualTo("encg:kept"); // retained, not cleared
        verify(cipher, never()).encrypt(any());
    }

    @Test
    void aBrandNewProviderMustCarryASecret() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(spec(null, "openid"))).isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsANonHttpsOrMalformedIssuerAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(
                new IdentityProviderSpec(ALIAS, "G", "http://accounts.google.com", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class); // not https
        assertThatThrownBy(() -> service.save(
                new IdentityProviderSpec(ALIAS, "G", "not a url", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class); // malformed
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsAnSsrfIssuerHostAndDoesNotPersist() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("169.254.169.254");

        assertThatThrownBy(() -> service.save(
                new IdentityProviderSpec(ALIAS, "G", "https://169.254.169.254", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsAMalformedAlias() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.save(
                new IdentityProviderSpec("bad_alias", "G", ISSUER, "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class); // underscore is not URL-safe
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsABlankDisplayNameClientIdOrIssuer() {
        // The service guard is independent of the controller's @NotBlank — a non-HTTP caller bypasses that layer.
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        lenient().when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(new IdentityProviderSpec(ALIAS, "  ", ISSUER, "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(new IdentityProviderSpec(ALIAS, "G", ISSUER, "  ", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save(new IdentityProviderSpec(ALIAS, "G", "  ", "c", "s", "openid", false, false, true)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void listReturnsTheActingTiersProviders() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdOrderByAlias(ORG)).thenReturn(List.of(row(ORG, "encg:cipher")));

        assertThat(service.list()).singleElement().extracting(IdentityProviderView::alias).isEqualTo(ALIAS);
    }

    @Test
    void aBoundOrglessNonPlatformCallerSeesNoProvidersAndCannotWrite() {
        // Symmetric read/write guard: an orgless non-platform caller owns nothing and must not reach the global
        // providers as if they were its own (a cross-tier leak), nor rewrite them.
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.save(spec("s", "openid"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(ALIAS)).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).findByOrgIdIsNullOrderByAlias(); // the global tier is never resolved as "own"
        verify(repository, never()).save(any());
    }

    @Test
    void thePlatformTierResolvesTheGlobalProviders() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNullOrderByAlias()).thenReturn(List.of(row(null, "encg:cipher")));

        assertThat(service.list()).hasSize(1);
    }

    @Test
    void deleteRemovesTheActingTiersProvider() {
        IdentityProvider existing = row(ORG, "encg:cipher");
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.delete(ALIAS);

        verify(repository).delete(existing);
    }

    @Test
    void deleteOfAnUnknownAliasIsASilentNoOp() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.empty());

        service.delete(ALIAS); // idempotent — not a 404

        verify(repository, never()).delete(any());
    }

    @Test
    void deleteOfAMalformedAliasIsRejectedBeforeTouchingTheRepository() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service.delete("bad_alias")).isInstanceOf(BadRequestException.class);
        verify(repository, never()).findByOrgIdAndAlias(any(), any());
        verify(repository, never()).delete(any());
    }

    // --- federated identities must not outlive the upstream that minted them -----------------------------

    /** Repointing an alias at a DIFFERENT IdP retires its identities: a colliding `sub` at the new upstream
     *  would otherwise inherit whichever account the old one had linked. */
    @Test
    void repointingAProviderAtAnotherUpstreamRetiresItsIdentities() {
        IdentityProvider existing = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123",
                "encg:cipher", "openid", true, false, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.save(new IdentityProviderSpec(ALIAS, "Google", "https://login.microsoftonline.test", "client-123",
                "s3cret", "openid", true, false, true));

        verify(links).unlinkAll(ORG, ISSUER, ALIAS); // the OLD issuer's identities, not the new one's
    }

    /** Under pairwise subject identifiers a client-id rotation renames every user's sub, so the old links
     *  would strand the whole tenant on the login path's fail-closed guard. */
    @Test
    void rotatingTheClientIdRetiresTheIdentitiesToo() {
        IdentityProvider existing = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123",
                "encg:cipher", "openid", true, false, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(links.unlinkAll(ORG, ISSUER, ALIAS)).thenReturn(List.of());

        service.save(new IdentityProviderSpec(ALIAS, "Google", ISSUER, "client-999", "s3cret", "openid",
                true, false, true));

        verify(links).unlinkAll(ORG, ISSUER, ALIAS);
    }


    @Test
    void editingAProviderWithoutChangingItsUpstreamKeepsItsIdentities() {
        IdentityProvider existing = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123",
                "encg:cipher", "openid", true, false, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.save(new IdentityProviderSpec(ALIAS, "Google Workspace", ISSUER, "client-123", "s3cret",
                "openid email", true, false, true));

        verify(links, never()).unlinkAll(any(), any(), any());
    }

    /**
     * Retiring an identity revokes an authentication credential. Deleting the row while the sessions it
     * authenticated are still alive is not revocation — the credential is gone and the access remains until
     * expiry, which is exactly the case an admin repoints a compromised upstream to prevent.
     */
    @Test
    void retiringIdentitiesTerminatesTheSessionsTheyAuthenticated() {
        IdentityProvider existing = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123",
                "encg:cipher", "openid", true, false, true);
        UUID retired = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(links.unlinkAll(ORG, ISSUER, ALIAS)).thenReturn(List.of(retired));
        when(users.usernamesOf(List.of(retired))).thenReturn(List.of("ada@example.com"));

        service.delete(ALIAS);

        verify(events).publishEvent(new UserAccessChangedEvent("ada@example.com", ORG));
    }

    @Test
    void retiringNothingTerminatesNothing() {
        IdentityProvider existing = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123",
                "encg:cipher", "openid", true, false, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));
        when(links.unlinkAll(ORG, ISSUER, ALIAS)).thenReturn(List.of());

        service.delete(ALIAS);

        verify(events, never()).publishEvent(any(UserAccessChangedEvent.class));
    }

    @Test
    void deletingAProviderRetiresItsIdentities() {
        IdentityProvider existing = IdentityProvider.create(ORG, ALIAS, "Google", ISSUER, "client-123",
                "encg:cipher", "openid", true, false, true);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndAlias(ORG, ALIAS)).thenReturn(Optional.of(existing));

        service.delete(ALIAS);

        verify(links).unlinkAll(ORG, ISSUER, ALIAS);
        verify(repository).delete(existing);
    }
}
