package com.example.sso.branding.internal.application;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.branding.internal.domain.AuthScreenCopy;
import com.example.sso.branding.internal.domain.AuthScreenCopyRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScreenCopyService} — written before it existed, so the cases below are what defines
 * it rather than a description of what it turned out to do.
 *
 * <p>The matrix: resolution (own over platform over nothing, per field, per screen), the editor read, writes
 * and their validation, deletes, and the tier guard on every mutating path. The tier guard gets its own case
 * per verb because a guard applied to update and forgotten on delete is the shape this module already had to
 * be careful about once.
 */
@ExtendWith(MockitoExtension.class)
class ScreenCopyServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    AuthScreenCopyRepository repository;
    @Mock
    OrgContext orgContext;
    @Mock
    ApplicationEventPublisher events;

    private ScreenCopyService service() {
        return new ScreenCopyService(repository, new ActingTier(orgContext), events);
    }

    private AuthScreenCopy row(UUID orgId, AuthScreen screen, ScreenCopy copy) {
        return AuthScreenCopy.create(orgId, screen, copy);
    }

    private ScreenCopy copy(String headline, String subtext, String footer, String helpUrl) {
        return new ScreenCopy(headline, subtext, footer, helpUrl);
    }

    // ---------------------------------------------------------------- resolve

    @Test
    void resolveReturnsNothingWhenNoTierHasWrittenAnything() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of());
        when(repository.findByOrgId(ORG)).thenReturn(List.of());

        assertThat(service().resolve(ORG)).isEmpty();
    }

    @Test
    void resolveReturnsTheTenantsOwnWording() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of());
        when(repository.findByOrgId(ORG)).thenReturn(List.of(
                row(ORG, AuthScreen.LOGIN, copy("Sign in to Acme", null, null, null))));

        Map<AuthScreen, ScreenCopy> resolved = service().resolve(ORG);

        assertThat(resolved).containsOnlyKeys(AuthScreen.LOGIN);
        assertThat(resolved.get(AuthScreen.LOGIN).headline()).isEqualTo("Sign in to Acme");
    }

    @Test
    void resolveFallsBackToThePlatformWordingForAScreenTheTenantHasNotWritten() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of(
                row(null, AuthScreen.MFA, copy("Verify it is you", null, null, null))));
        when(repository.findByOrgId(ORG)).thenReturn(List.of(
                row(ORG, AuthScreen.LOGIN, copy("Sign in to Acme", null, null, null))));

        Map<AuthScreen, ScreenCopy> resolved = service().resolve(ORG);

        assertThat(resolved).containsOnlyKeys(AuthScreen.LOGIN, AuthScreen.MFA);
        assertThat(resolved.get(AuthScreen.MFA).headline()).isEqualTo("Verify it is you");
    }

    /**
     * The same per-FIELD rule the theme uses. A tenant that replaces only the headline must keep the
     * platform's footer for that screen, not lose it because its own row exists.
     */
    @Test
    void resolveTakesEachFieldFromTheNearestTierThatSetsIt() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of(
                row(null, AuthScreen.LOGIN, copy("Platform heading", "Platform subtext", "Need help?", null))));
        when(repository.findByOrgId(ORG)).thenReturn(List.of(
                row(ORG, AuthScreen.LOGIN, copy("Sign in to Acme", null, null, null))));

        ScreenCopy login = service().resolve(ORG).get(AuthScreen.LOGIN);

        assertThat(login.headline()).isEqualTo("Sign in to Acme");   // own wins
        assertThat(login.subtext()).isEqualTo("Platform subtext");   // inherited
        assertThat(login.footer()).isEqualTo("Need help?");          // inherited
    }

    /** An all-null row must not reach the client as an object that says nothing. */
    @Test
    void resolveDropsAScreenWhoseRowCarriesNoWording() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of());
        when(repository.findByOrgId(ORG)).thenReturn(List.of(row(ORG, AuthScreen.LOGIN, ScreenCopy.none())));

        assertThat(service().resolve(ORG)).isEmpty();
    }

    @Test
    void resolveWithANullOrgReadsOnlyThePlatformWording() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of(
                row(null, AuthScreen.CONSENT, copy(null, "Review what this application is requesting", null, null))));

        assertThat(service().resolve(null)).containsOnlyKeys(AuthScreen.CONSENT);
        verify(repository, never()).findByOrgId(any());
    }

    // -------------------------------------------------------------------- get

    @Test
    void getReturnsTheActingTiersOwnRows() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(List.of(
                row(ORG, AuthScreen.LOGIN, copy("Sign in to Acme", null, null, null))));

        assertThat(service().get()).containsOnlyKeys(AuthScreen.LOGIN);
    }

    /**
     * Symmetric with the write guard: a bound-but-orgless non-platform caller owns no row, so the global one
     * must not be handed back as though it were theirs to edit.
     */
    @Test
    void getDoesNotSurfaceTheGlobalRowsToABoundOrglessNonPlatformCaller() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThat(service().get()).isEmpty();
        verify(repository, never()).findByOrgIdIsNull();
        verify(repository, never()).findByOrgId(any());
    }

    @Test
    void getReturnsTheGlobalRowsForThePlatformTier() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNull()).thenReturn(List.of(
                row(null, AuthScreen.STEPUP, copy("Confirm it is you", null, null, null))));

        assertThat(service().get()).containsOnlyKeys(AuthScreen.STEPUP);
    }

    // ----------------------------------------------------------------- update

    @Test
    void updateSavesANewRowForTheActingTenant() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().update(AuthScreen.LOGIN, copy("Sign in to Acme", "Use your work account", null, null));

        ArgumentCaptor<AuthScreenCopy> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getScreen()).isEqualTo(AuthScreen.LOGIN);
        assertThat(saved.getValue().copy().headline()).isEqualTo("Sign in to Acme");
    }

    @Test
    void updateReconfiguresAnExistingRowInPlace() {
        AuthScreenCopy existing = row(ORG, AuthScreen.LOGIN, copy("Old", "Old sub", null, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.of(existing));

        service().update(AuthScreen.LOGIN, copy("New", null, null, null));

        verify(repository, never()).save(any());
        assertThat(existing.copy().headline()).isEqualTo("New");
        assertThat(existing.copy().subtext()).isNull(); // blank clears, returning that piece to inheriting
    }

    @Test
    void updateWritesTheGlobalRowForThePlatformTier() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNullAndScreen(AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().update(AuthScreen.LOGIN, copy("Platform heading", null, null, null));

        ArgumentCaptor<AuthScreenCopy> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isNull();
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalWording() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN, copy("X", null, null, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------- validation

    /** Each length cap asserted on its OWN field: one "rejects something too long" would hide three gaps. */
    @Test
    void updateRejectsEachOverlongFieldSeparately() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN, copy("x".repeat(121), null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN, copy(null, "x".repeat(301), null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN, copy(null, null, "x".repeat(201), null)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    /**
     * The three layers — @Pattern, this service, the V146 column CHECK — must agree on CASE. The service used
     * to compare case-insensitively and store the original casing, so an uppercase scheme passed here and was
     * refused by the column at commit-flush: a 500 on a value this layer had just called valid.
     */
    @Test
    void updateRejectsAnUppercaseSchemeJustAsTheColumnDoes() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN,
                copy(null, null, null, "HTTPS://help.acme")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsANonHttpsHelpUrl() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN, copy(null, null, null, "http://help.acme")))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    /** Boundary: exactly at the cap is accepted, so the check is `>` and not `>=`. */
    @Test
    void updateAcceptsAFieldExactlyAtItsCap() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().update(AuthScreen.LOGIN, copy("x".repeat(120), "y".repeat(300), "z".repeat(200), null));

        verify(repository).save(any());
    }

    /** Whitespace is not content: a field of spaces clears, it does not store a blank that renders as a gap. */
    @Test
    void updateTreatsAWhitespaceOnlyFieldAsCleared() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().update(AuthScreen.LOGIN, copy("   ", null, null, null));

        ArgumentCaptor<AuthScreenCopy> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().copy().headline()).isNull();
    }

    // ----------------------------------------------------------------- delete

    @Test
    void deleteRemovesTheActingTenantsRowForThatScreen() {
        AuthScreenCopy existing = row(ORG, AuthScreen.LOGIN, copy("Sign in to Acme", null, null, null));
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.of(existing));

        service().delete(AuthScreen.LOGIN);

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsANoOpWhenTheTierHasNotWrittenThatScreen() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().delete(AuthScreen.LOGIN);

        verify(repository, never()).delete(any());
    }

    /** The guard belongs on EVERY mutating verb, not only the one it was first written for. */
    @Test
    void aBoundOrglessNonPlatformCallerCannotDelete() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().delete(AuthScreen.LOGIN)).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }

    // ------------------------------------------------- cache invalidation

    /**
     * This service is a SECOND writer whose output is baked into the cached Branding, so its announcements
     * matter exactly as much as BrandingService's — and nothing pinned them until a review noticed that
     * deleting either publish left the suite green and the fleet serving stale sign-in wording.
     */
    @Test
    void updateAnnouncesTheChange() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().update(AuthScreen.LOGIN, copy("Sign in to Acme", null, null, null));

        verify(events).publishEvent(new BrandingChanged());
    }

    @Test
    void deleteAnnouncesTheChange() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().delete(AuthScreen.LOGIN);

        verify(events).publishEvent(new BrandingChanged());
    }

    /** A PLATFORM write announces too: every tenant inherits its wording, so every entry must retire. */
    @Test
    void aPlatformWriteAnnouncesTheChange() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNullAndScreen(AuthScreen.LOGIN)).thenReturn(Optional.empty());

        service().update(AuthScreen.LOGIN, copy("Platform heading", null, null, null));

        verify(events).publishEvent(new BrandingChanged());
    }

    /** A refused write must announce NOTHING — retiring the cache on a rejected edit is a free cold start. */
    @Test
    void aRefusedWriteAnnouncesNothing() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().update(AuthScreen.LOGIN, copy("X", null, null, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(events, never()).publishEvent(any(BrandingChanged.class));
    }

    // ------------------------------------------- screens that own their title

    /**
     * The consent title names the client asking for access, so a tenant may not replace it. Enforced HERE and
     * not only in the renderer: the server otherwise accepts, stores and publishes the value on the public
     * endpoint, leaving one component's constant as the only thing between it and a phishing screen on the
     * IdP's own origin.
     */
    @Test
    void aHeadlineForAScreenThatOwnsItsTitleIsRefused() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(AuthScreen.CONSENT,
                copy("Approve to continue", null, null, null)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    /** The tenant still speaks on that screen — only the title is the IdP's. */
    @Test
    void theOtherFieldsOfThatScreenAreStillAccepted() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgIdAndScreen(ORG, AuthScreen.CONSENT)).thenReturn(Optional.empty());

        service().update(AuthScreen.CONSENT, copy(null, "Acme uses this to sync", "Questions?", null));

        ArgumentCaptor<AuthScreenCopy> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().copy().subtext()).isEqualTo("Acme uses this to sync");
    }

    /** Every other screen keeps taking a headline — the rule is per screen, not a global ban. */
    @Test
    void aHeadlineIsAcceptedForEveryScreenThatAllowsOne() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        for (AuthScreen screen : AuthScreen.values()) {
            if (!screen.allowsCustomHeadline()) {
                continue;
            }
            when(repository.findByOrgIdAndScreen(ORG, screen)).thenReturn(Optional.empty());
            service().update(screen, copy("Our own heading", null, null, null));
        }
    }

    /**
     * The read side must not serve a consent headline either, whatever put it in the table. V146 states that
     * threat model for the URL columns — a migration, a manual fix-up, a writer that skips this service — and
     * the headline rule deserves the same treatment on the path that renders it.
     */
    @Test
    void aStoredConsentHeadlineIsNotServed() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of());
        when(repository.findByOrgId(ORG)).thenReturn(List.of(
                row(ORG, AuthScreen.CONSENT, copy("Approve to continue", "Acme syncs your profile", null, null))));

        ScreenCopy consent = service().resolve(ORG).get(AuthScreen.CONSENT);

        assertThat(consent.headline()).isNull();
        assertThat(consent.subtext()).isEqualTo("Acme syncs your profile"); // the rest still speaks
    }

    /** A row carrying ONLY a forbidden headline says nothing once stripped, so the screen drops entirely. */
    @Test
    void aRowCarryingOnlyAForbiddenHeadlineDisappears() {
        when(repository.findByOrgIdIsNull()).thenReturn(List.of());
        when(repository.findByOrgId(ORG)).thenReturn(List.of(
                row(ORG, AuthScreen.CONSENT, copy("Approve to continue", null, null, null))));

        assertThat(service().resolve(ORG)).isEmpty();
    }
}
