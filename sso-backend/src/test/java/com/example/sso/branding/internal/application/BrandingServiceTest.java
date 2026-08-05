package com.example.sso.branding.internal.application;

import com.example.sso.branding.AuthScreenLayout;
import com.example.sso.branding.Branding;
import java.util.Map;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.BrandingCorner;
import com.example.sso.branding.BrandingFont;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.internal.domain.OrgBranding;
import com.example.sso.branding.internal.domain.OrgBrandingRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BrandingService}: own→platform→built-in resolution PER FIELD, the fail-closed
 * platform-write guard (symmetric on read and write), delete reverting to the default, and shape validation
 * (https URLs, #RRGGBB colours, capped name) refusing — and not persisting — a bad value.
 */
@ExtendWith(MockitoExtension.class)
class BrandingServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    OrgBrandingRepository repository;
    @Mock
    OrgContext orgContext;
    @Mock
    ScreenCopyService screenCopy;
    @Mock
    BrandingCache cache;
    @Mock
    ApplicationEventPublisher events;

    private BrandingService service() {
        return new BrandingService(repository, new ActingTier(orgContext), screenCopy, cache, events);
    }

    /** A fully-populated row, so a test can assert a field is INHERITED rather than merely absent everywhere. */
    private OrgBranding row(UUID orgId, String productName, String accent) {
        return OrgBranding.create(orgId,
                new BrandingIdentity("https://cdn.acme.example/logo.png", null, null, productName),
                new BrandingTheme(accent, null, null, null, null, null));
    }

    private OrgBranding row(UUID orgId) {
        return row(orgId, "Acme", "#123abc");
    }

    private BrandingSpec spec(BrandingIdentity identity, BrandingTheme theme) {
        return new BrandingSpec(identity, theme);
    }

    private BrandingSpec spec(String logoUrl, String accent, String name) {
        return spec(new BrandingIdentity(logoUrl, null, null, name),
                new BrandingTheme(accent, null, null, null, null, null));
    }

    @Test
    void resolveReturnsTheOrgsOwnBranding() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG)));
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        Branding branding = service().resolve(ORG);

        assertThat(branding.productName()).isEqualTo("Acme");
        assertThat(branding.theme().accentColor()).isEqualTo("#123abc");
    }

    @Test
    void resolveFallsBackToTheGlobalRowThenTheBuiltInDefault() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null)), Optional.empty());

        assertThat(service().resolve(ORG).productName()).isEqualTo("Acme"); // inherits global
        assertThat(service().resolve(ORG).productName()).isEqualTo("Svalinn"); // built-in default
    }

    /**
     * The point of per-FIELD resolution: an own row that sets only its name must not discard the platform's
     * accent. Under the previous per-ROW rule the own row won wholesale and this accent came back null.
     */
    @Test
    void resolveTakesEachFieldFromTheNearestTierThatSetsIt() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(
                OrgBranding.create(ORG, new BrandingIdentity(null, null, null, "Acme"), BrandingTheme.none())));
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null, "Platform", "#abcdef")));

        Branding branding = service().resolve(ORG);

        assertThat(branding.productName()).isEqualTo("Acme");                          // own wins
        assertThat(branding.theme().accentColor()).isEqualTo("#abcdef");               // inherited from platform
        assertThat(branding.identity().logoUrl()).isEqualTo("https://cdn.acme.example/logo.png");
    }

    /** A tier that sets nothing still renders: the built-in default supplies the three style choices. */
    @Test
    void resolveSuppliesTheBuiltInStyleDefaultsWhenNobodyHasChosen() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        BrandingTheme theme = service().resolve(ORG).theme();

        assertThat(theme.font()).isEqualTo(BrandingFont.SANS);
        assertThat(theme.corner()).isEqualTo(BrandingCorner.SOFT);
        assertThat(theme.layout()).isEqualTo(AuthScreenLayout.CENTERED);
    }

    @Test
    void resolveWithANullOrgReadsOnlyTheGlobalRow() {
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        assertThat(service().resolve(null).productName()).isEqualTo("Svalinn");
        verify(repository, never()).findByOrgId(any());
    }

    @Test
    void getReturnsTheOwnRowConfigured() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG)));

        BrandingView view = service().get();

        assertThat(view.configured()).isTrue();
        assertThat(view.identity().productName()).isEqualTo("Acme");
    }

    @Test
    void getIsNotConfiguredAndShowsTheInheritedDefaultAsAStartingPoint() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        BrandingView view = service().get();

        assertThat(view.configured()).isFalse();
        assertThat(view.identity().productName()).isEqualTo("Svalinn"); // built-in, as a starting point
    }

    @Test
    void getDoesNotSurfaceTheGlobalRowToABoundOrglessNonPlatformCaller() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);
        // A global row EXISTS (read here only as the inherited starting-point, NOT as "own"): the mutation this
        // catches is ownRow() returning it as own → configured=true for a non-platform caller.
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null)));

        assertThat(service().get().configured()).isFalse();
        verify(repository, never()).findByOrgId(any()); // an orgless caller never queries by a specific org
    }

    @Test
    void getReturnsTheGlobalRowAsConfiguredForThePlatformTier() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true); // the platform tier OWNS the global row
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null)));

        BrandingView view = service().get();

        assertThat(view.configured()).isTrue();
        assertThat(view.identity().productName()).isEqualTo("Acme");
    }

    @Test
    void updateSavesTheActingTenantsBranding() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());

        service().update(spec("https://cdn.acme.example/l.png", "#abcdef", "Acme"));

        ArgumentCaptor<OrgBranding> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().theme().accentColor()).isEqualTo("#abcdef");
    }

    /** The style choices are enums by the time they arrive, so update persists them without re-deciding. */
    @Test
    void updatePersistsTheStyleChoices() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());

        service().update(spec(BrandingIdentity.none(),
                new BrandingTheme(null, "#101010", "https://cdn.acme.example/bg.jpg",
                        BrandingFont.SERIF, BrandingCorner.ROUND, AuthScreenLayout.SPLIT)));

        ArgumentCaptor<OrgBranding> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        BrandingTheme theme = saved.getValue().theme();
        assertThat(theme.font()).isEqualTo(BrandingFont.SERIF);
        assertThat(theme.corner()).isEqualTo(BrandingCorner.ROUND);
        assertThat(theme.layout()).isEqualTo(AuthScreenLayout.SPLIT);
        assertThat(theme.backgroundColor()).isEqualTo("#101010");
        assertThat(theme.backgroundImageUrl()).isEqualTo("https://cdn.acme.example/bg.jpg");
    }

    @Test
    void updateReconfiguresAnExistingRowInPlace() {
        OrgBranding existing = row(ORG);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        service().update(spec(null, "#000000", "Rebrand"));

        verify(repository, never()).save(any());
        assertThat(existing.identity().productName()).isEqualTo("Rebrand");
        assertThat(existing.identity().logoUrl()).isNull(); // blank logo cleared
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().update(spec("https://x.example/l.png", "#123456", "X")))
                .isInstanceOf(ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsANonHttpsLogoABadAccentAndALongName() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(spec("http://cdn/l.png", "#123456", "X")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(spec(null, "red", "X")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(spec(null, "#GGGGGG", "X")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(spec(null, "#123456", "x".repeat(65))))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    /**
     * Each new URL is checked on its OWN dimension. A single "rejects a bad URL" test would pass while two of
     * the three fields went unvalidated, which is exactly the shape this asserts against.
     */
    @Test
    void updateRejectsANonHttpsUrlInEveryUrlField() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(spec(
                new BrandingIdentity(null, "http://cdn/dark.png", null, null), BrandingTheme.none())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(spec(
                new BrandingIdentity(null, null, "http://cdn/fav.ico", null), BrandingTheme.none())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service().update(spec(BrandingIdentity.none(),
                new BrandingTheme(null, null, "http://cdn/bg.jpg", null, null, null))))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateRejectsABadBackgroundColour() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));

        assertThatThrownBy(() -> service().update(spec(BrandingIdentity.none(),
                new BrandingTheme(null, "rgb(0,0,0)", null, null, null, null))))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void deleteRemovesTheActingTenantsRow() {
        OrgBranding existing = row(ORG);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        service().delete();

        verify(repository).delete(existing);
    }

    @Test
    void deleteIsANoOpWhenTheTierHasNoOwnRow() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());

        service().delete();

        verify(repository, never()).delete(any());
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotDelete() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().delete()).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }

    /**
     * The screen wording rides along on the SAME resolve, because the SPA fetches branding once at the root
     * and every auth screen reads from that one answer. A second endpoint would mean a second round trip
     * before the login screen could draw its own heading.
     */
    @Test
    void resolveCarriesTheScreenWording() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());
        when(screenCopy.resolve(ORG)).thenReturn(Map.of(AuthScreen.LOGIN,
                new ScreenCopy("Sign in to Acme", null, null, null)));

        Branding branding = service().resolve(ORG);

        assertThat(branding.copy()).containsOnlyKeys(AuthScreen.LOGIN);
        assertThat(branding.copy().get(AuthScreen.LOGIN).headline()).isEqualTo("Sign in to Acme");
    }

    /** A tier that has written no wording still resolves — an empty map, never a null the client must guard. */
    @Test
    void resolveCarriesAnEmptyMapWhenNobodyHasWrittenAnyWording() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());
        when(screenCopy.resolve(ORG)).thenReturn(Map.of());

        assertThat(service().resolve(ORG).copy()).isEmpty();
    }

    /** The built-in default has no wording of its own: the screens carry their own translated strings. */
    @Test
    void thePlatformDefaultCarriesNoScreenWording() {
        assertThat(Branding.platformDefault().copy()).isEmpty();
    }

    // ------------------------------------------------------------------ cache

    /** A cache HIT must answer without touching the database at all — otherwise it is not saving anything. */
    @Test
    void resolveServesACachedBrandingWithoutQueryingTheDatabase() {
        when(cache.find(0, ORG)).thenReturn(Optional.of(Branding.platformDefault()));

        assertThat(service().resolve(ORG).productName()).isEqualTo("Svalinn");

        verify(repository, never()).findByOrgId(any());
        verify(repository, never()).findByOrgIdIsNull();
        verify(screenCopy, never()).resolve(any());
    }

    @Test
    void resolveStoresWhatItResolvedOnAMiss() {
        when(cache.find(0, ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG)));
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());
        when(screenCopy.resolve(ORG)).thenReturn(Map.of());

        service().resolve(ORG);

        ArgumentCaptor<Branding> stored = ArgumentCaptor.captor();
        verify(cache).put(eq(0L), eq(ORG), stored.capture());
        assertThat(stored.getValue().productName()).isEqualTo("Acme");
    }

    /**
     * The eviction is announced, not performed: an AFTER_COMMIT listener does it, because evicting inside the
     * transaction lets a concurrent read re-cache the row as it stands before the commit.
     */
    @Test
    void updateAnnouncesTheChangeRatherThanEvictingInline() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());

        service().update(spec("https://cdn.acme.example/l.png", "#abcdef", "Acme"));

        verify(events).publishEvent(new BrandingChanged());
        verify(cache, never()).invalidate();
    }

    @Test
    void deleteAnnouncesTheChange() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG)));

        service().delete();

        verify(events).publishEvent(new BrandingChanged());
    }

    /** A PLATFORM write announces too — every tenant inherits its row, so every entry must retire. */
    @Test
    void aPlatformWriteAnnouncesTheWholeTier() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(true);
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        service().update(spec("https://cdn.example/l.png", "#abcdef", "Platform"));

        verify(events).publishEvent(new BrandingChanged());
    }
}
