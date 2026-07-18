package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.internal.domain.OrgBranding;
import com.example.sso.branding.internal.domain.OrgBrandingRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-tenant auth-UI branding: {@link #resolve} answers the branding to RENDER for an org (own → platform →
 * built-in default); {@code get}/{@code update}/{@code delete} are the admin surface. Writes go only to the
 * acting tier's own row via the fail-closed {@link #writableOrg} (a bound-but-orgless non-platform caller can't
 * edit the global default); the read guard {@link #ownRow} is symmetric. Values are validated for shape (https
 * logo, {@code #RRGGBB} accent, capped name) so a downstream surface can inject them with escaping and no
 * breakout. Nothing here is a secret — branding is shown to every visitor of the tenant's subdomain.
 */
@Service
@RequiredArgsConstructor
public class BrandingService {

    private static final Pattern ACCENT = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final int MAX_PRODUCT_NAME = 64;
    private static final int MAX_LOGO_URL = 2048;

    private final OrgBrandingRepository repository;
    private final OrgContext orgContext;

    /** The branding to render for {@code orgId}: own row → platform override → built-in default. */
    @Transactional(readOnly = true)
    public Branding resolve(UUID orgId) {
        Optional<OrgBranding> row = orgId != null
                ? repository.findByOrgId(orgId).or(repository::findByOrgIdIsNull)
                : repository.findByOrgIdIsNull();
        return row.map(this::toBranding).orElseGet(Branding::platformDefault);
    }

    /** The acting tier's OWN branding for the editor (or the inherited default as a starting point). */
    @Transactional(readOnly = true)
    public BrandingView get() {
        return ownRow().map(BrandingView::configured)
                .orElseGet(() -> BrandingView.inherited(inheritedDefault()));
    }

    /** Registers/updates the acting tier's branding (validated: https logo, #RRGGBB accent, capped name). */
    @Transactional
    public void update(BrandingSpec spec) {
        UUID org = writableOrg();
        validate(spec);
        ownRow().ifPresentOrElse(
                row -> row.reconfigure(trimToNull(spec.logoUrl()), trimToNull(spec.accentColor()),
                        trimToNull(spec.productName())),
                () -> repository.save(OrgBranding.create(org, trimToNull(spec.logoUrl()),
                        trimToNull(spec.accentColor()), trimToNull(spec.productName()))));
    }

    /** Drops the acting tier's branding — its screens revert to the platform/built-in default. */
    @Transactional
    public void delete() {
        writableOrg();
        ownRow().ifPresent(repository::delete);
    }

    private void validate(BrandingSpec spec) {
        if (StringUtils.hasText(spec.logoUrl())) {
            String logo = spec.logoUrl().trim();
            if (!logo.toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new BadRequestException("The logo URL must be an https URL.");
            }
            if (logo.length() > MAX_LOGO_URL) {
                throw new BadRequestException("The logo URL is too long.");
            }
        }
        if (StringUtils.hasText(spec.accentColor()) && !ACCENT.matcher(spec.accentColor().trim()).matches()) {
            throw new BadRequestException("The accent color must be a #RRGGBB hex value.");
        }
        if (StringUtils.hasText(spec.productName()) && spec.productName().trim().length() > MAX_PRODUCT_NAME) {
            throw new BadRequestException("The product name is too long.");
        }
    }

    private Branding inheritedDefault() {
        return repository.findByOrgIdIsNull().map(this::toBranding).orElseGet(Branding::platformDefault);
    }

    /** The acting tier's OWN row — the platform tier owns the global (org_id NULL) row, a bound-orgless tenant none. */
    private Optional<OrgBranding> ownRow() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return repository.findByOrgId(org);
        }
        return orgContext.isPlatform() ? repository.findByOrgIdIsNull() : Optional.empty();
    }

    /** The acting org for a WRITE. Deny-by-default: a bound-but-orgless non-platform caller can't write global. */
    private UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            throw new ForbiddenException("Only a platform administrator may edit the global branding.");
        }
        return org;
    }

    private Branding toBranding(OrgBranding branding) {
        return new Branding(branding.getLogoUrl(), branding.getAccentColor(), branding.getProductName());
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
