package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BrandingService}: own→platform→built-in resolution, the fail-closed platform-write
 * guard (symmetric on read and write), delete reverting to the default, and the shape validation (https logo,
 * #RRGGBB accent, capped name) refusing — and not persisting — a bad value.
 */
@ExtendWith(MockitoExtension.class)
class BrandingServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    OrgBrandingRepository repository;
    @Mock
    OrgContext orgContext;

    private BrandingService service() {
        return new BrandingService(repository, orgContext);
    }

    private OrgBranding row(UUID orgId) {
        return OrgBranding.create(orgId, "https://cdn.acme.example/logo.png", "#123abc", "Acme");
    }

    private BrandingSpec spec(String logoUrl, String accent, String name) {
        return new BrandingSpec(logoUrl, accent, name);
    }

    @Test
    void resolveReturnsTheOrgsOwnBranding() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG)));

        Branding branding = service().resolve(ORG);

        assertThat(branding.productName()).isEqualTo("Acme");
        assertThat(branding.accentColor()).isEqualTo("#123abc");
    }

    @Test
    void resolveFallsBackToTheGlobalRowThenTheBuiltInDefault() {
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.of(row(null)), Optional.empty());

        assertThat(service().resolve(ORG).productName()).isEqualTo("Acme"); // inherits global
        assertThat(service().resolve(ORG).productName()).isEqualTo("Mini SSO"); // built-in default
    }

    @Test
    void resolveWithANullOrgReadsOnlyTheGlobalRow() {
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        assertThat(service().resolve(null).productName()).isEqualTo("Mini SSO");
        verify(repository, never()).findByOrgId(any());
    }

    @Test
    void getReturnsTheOwnRowConfigured() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(row(ORG)));

        BrandingView view = service().get();

        assertThat(view.configured()).isTrue();
        assertThat(view.productName()).isEqualTo("Acme");
    }

    @Test
    void getIsNotConfiguredAndShowsTheInheritedDefaultAsAStartingPoint() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());
        when(repository.findByOrgIdIsNull()).thenReturn(Optional.empty());

        BrandingView view = service().get();

        assertThat(view.configured()).isFalse();
        assertThat(view.productName()).isEqualTo("Mini SSO"); // the built-in default, as a starting point
    }

    @Test
    void getDoesNotSurfaceTheGlobalRowToABoundOrglessNonPlatformCaller() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThat(service().get().configured()).isFalse();
        verify(repository, never()).findByOrgId(any()); // never resolves the global row as "own"
    }

    @Test
    void updateSavesTheActingTenantsBranding() {
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.empty());

        service().update(spec("https://cdn.acme.example/l.png", "#abcdef", "Acme"));

        ArgumentCaptor<OrgBranding> saved = ArgumentCaptor.captor();
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(saved.getValue().getAccentColor()).isEqualTo("#abcdef");
    }

    @Test
    void updateReconfiguresAnExistingRowInPlace() {
        OrgBranding existing = row(ORG);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        service().update(spec(null, "#000000", "Rebrand"));

        verify(repository, never()).save(any());
        assertThat(existing.getProductName()).isEqualTo("Rebrand");
        assertThat(existing.getLogoUrl()).isNull(); // blank logo cleared
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

    @Test
    void deleteRemovesTheActingTenantsRow() {
        OrgBranding existing = row(ORG);
        when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
        when(repository.findByOrgId(ORG)).thenReturn(Optional.of(existing));

        service().delete();

        verify(repository).delete(existing);
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotDelete() {
        when(orgContext.currentOrg()).thenReturn(Optional.empty());
        when(orgContext.isPlatform()).thenReturn(false);

        assertThatThrownBy(() -> service().delete()).isInstanceOf(ForbiddenException.class);
        verify(repository, never()).delete(any());
    }
}
