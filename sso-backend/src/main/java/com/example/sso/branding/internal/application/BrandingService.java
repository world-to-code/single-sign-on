package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingResolver;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.internal.domain.OrgBranding;
import com.example.sso.branding.internal.domain.OrgBrandingRepository;
import com.example.sso.shared.error.BadRequestException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant auth-UI branding: {@link #resolve} answers the branding to RENDER for an org (own → platform →
 * built-in default, per FIELD); {@code get}/{@code update}/{@code delete} are the admin surface. Writes go only
 * to the acting tier's own row via {@link ActingTier}'s fail-closed write guard (a bound-but-orgless
 * non-platform caller can't edit the global default); the read guard {@link #ownRow} is symmetric. Free-text values are
 * validated for shape (https URLs, {@code #RRGGBB} colours, capped name) so a downstream surface can inject
 * them with escaping and no breakout; the three style choices are enums, so their safety is structural.
 * Nothing here is a secret — branding is shown to every visitor of the tenant's subdomain.
 */
@Service
@RequiredArgsConstructor
public class BrandingService implements BrandingResolver {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int MAX_PRODUCT_NAME = 64;

    private final OrgBrandingRepository repository;
    private final ActingTier tier;
    private final ScreenCopyService screenCopy;
    private final BrandingCache cache;
    private final ApplicationEventPublisher events;

    /**
     * The branding to render for {@code orgId}: own row over platform row over built-in default, resolved
     * field by field. A tenant that has set only its background therefore keeps the platform's accent instead
     * of dropping the whole platform row on the floor.
     */
    @Override
    @Transactional(readOnly = true)
    public Branding resolve(UUID orgId) {
        // Captured BEFORE the database read, and reused for the store below: a write that commits in between
        // bumps the generation, so what this stores lands under a number nothing will ask for again.
        long generation = cache.generation();
        Optional<Branding> cached = cache.find(generation, orgId);
        if (cached.isPresent()) {
            return cached.get();
        }
        Branding platform = platformBranding();
        Branding resolved = orgId == null ? platform : repository.findByOrgId(orgId)
                .map(this::toBranding)
                .map(own -> own.inheriting(platform))
                .orElse(platform);
        Branding answer = resolved.withCopy(screenCopy.resolve(orgId));
        cache.put(generation, orgId, answer);
        return answer;
    }

    /** The acting tier's OWN branding for the editor (or the inherited default as a starting point). */
    @Transactional(readOnly = true)
    public BrandingView get() {
        return ownRow().map(BrandingView::configured)
                .orElseGet(() -> BrandingView.inherited(inheritedDefault()));
    }

    /** Registers/updates the acting tier's branding (validated: https URLs, #RRGGBB colours, capped name). */
    @Transactional
    public void update(BrandingSpec spec) {
        UUID org = tier.writableOrg();
        BrandingIdentity identity = validated(spec.identity());
        BrandingTheme theme = validated(spec.theme());
        ownRow().ifPresentOrElse(
                row -> row.reconfigure(identity, theme),
                () -> repository.save(OrgBranding.create(org, identity, theme)));
        events.publishEvent(new BrandingChanged());
    }

    /** Drops the acting tier's branding — its screens revert to the platform/built-in default. */
    @Transactional
    public void delete() {
        tier.writableOrg();
        ownRow().ifPresent(repository::delete);
        events.publishEvent(new BrandingChanged());
    }

    /** Trimmed and shape-checked; a blank field becomes null, which is how a piece returns to inheriting. */
    private BrandingIdentity validated(BrandingIdentity identity) {
        return new BrandingIdentity(
                BrandingValues.httpsUrl(identity.logoUrl(), "branding.logoUrl"),
                BrandingValues.httpsUrl(identity.logoUrlDark(), "branding.logoUrlDark"),
                BrandingValues.httpsUrl(identity.faviconUrl(), "branding.faviconUrl"),
                productName(identity.productName()));
    }

    private BrandingTheme validated(BrandingTheme theme) {
        return new BrandingTheme(
                color(theme.accentColor(), "branding.accentColor.invalid"),
                color(theme.backgroundColor(), "branding.backgroundColor.invalid"),
                BrandingValues.httpsUrl(theme.backgroundImageUrl(), "branding.backgroundImageUrl"),
                theme.font(), theme.corner(), theme.layout());
    }

    private String color(String value, String messageKey) {
        String trimmed = BrandingValues.trimToNull(value);
        if (trimmed != null && !COLOR.matcher(trimmed).matches()) {
            throw BadRequestException.of(messageKey);
        }
        return trimmed;
    }

    private String productName(String value) {
        return BrandingValues.capped(value, MAX_PRODUCT_NAME, "branding.productName.tooLong");
    }

    /**
     * Deliberately NOT {@link #resolve}: the editor's starting point is read right after a save, and the
     * cache entry is only evicted once that transaction commits. Reading through the cache here would show an
     * administrator the value they just replaced.
     */
    private Branding inheritedDefault() {
        return platformBranding();
    }

    /** The platform row over the built-in default. One definition: the editor's starting point and what the
     * sign-in screen actually renders must not be able to diverge when the fallback rule changes. */
    private Branding platformBranding() {
        return repository.findByOrgIdIsNull()
                .map(this::toBranding)
                .map(row -> row.inheriting(Branding.platformDefault()))
                .orElseGet(Branding::platformDefault);
    }

    /** The acting tier's OWN row — the platform tier owns the global (org_id NULL) row, a bound-orgless tenant none. */
    private Optional<OrgBranding> ownRow() {
        UUID org = tier.org().orElse(null);
        if (org != null) {
            return repository.findByOrgId(org);
        }
        return tier.ownsGlobalRow() ? repository.findByOrgIdIsNull() : Optional.empty();
    }

    /** A row carries no wording — that lives in its own table — so this stage resolves without it. */
    private Branding toBranding(OrgBranding branding) {
        return new Branding(branding.identity(), branding.theme(), Map.of());
    }

}
