package com.example.sso.branding.internal.domain;

import com.example.sso.branding.AuthScreenLayout;
import com.example.sso.branding.BrandingCorner;
import com.example.sso.branding.BrandingFont;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tenant's auth-UI branding override — its marks and name, and the theme its sign-in screens render with. A
 * {@code null} {@link #orgId} is the platform-wide default; a non-null one is that tenant's override. A tier
 * with no row inherits the platform row, else the built-in default. Every field is optional, and an absent one
 * inherits INDEPENDENTLY of its neighbours.  Public data — nothing here is a secret.
 *
 * <p>Callers read the row back as {@link #identity()} / {@link #theme()} rather than through ten getters: what
 * a caller wants is "this tenant's identity" or "this tenant's look", and exposing the columns one by one
 * would push that grouping into every call site. The enum columns are stored as names and V144 carries a
 * matching CHECK, so the closed set holds for any writer, not only for this class.
 */
@Entity
@Table(name = "org_branding")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrgBranding extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "logo_url_dark")
    private String logoUrlDark;

    @Column(name = "favicon_url")
    private String faviconUrl;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "accent_color")
    private String accentColor;

    @Column(name = "background_color")
    private String backgroundColor;

    @Column(name = "background_image_url")
    private String backgroundImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "font_family")
    private BrandingFont font;

    @Enumerated(EnumType.STRING)
    @Column(name = "corner_style")
    private BrandingCorner corner;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_layout")
    private AuthScreenLayout layout;

    /** Owning tenant, or {@code null} for the platform-wide default row. */
    public static OrgBranding create(UUID orgId, BrandingIdentity identity, BrandingTheme theme) {
        OrgBranding branding = new OrgBranding();
        branding.orgId = orgId;
        branding.apply(identity, theme);
        return branding;
    }

    /** Replace this row's branding (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(BrandingIdentity identity, BrandingTheme theme) {
        apply(identity, theme);
    }

    /** This row's marks and name. */
    public BrandingIdentity identity() {
        return new BrandingIdentity(logoUrl, logoUrlDark, faviconUrl, productName);
    }

    /** This row's look. */
    public BrandingTheme theme() {
        return new BrandingTheme(accentColor, backgroundColor, backgroundImageUrl, font, corner, layout);
    }

    private void apply(BrandingIdentity identity, BrandingTheme theme) {
        this.logoUrl = identity.logoUrl();
        this.logoUrlDark = identity.logoUrlDark();
        this.faviconUrl = identity.faviconUrl();
        this.productName = identity.productName();
        this.accentColor = theme.accentColor();
        this.backgroundColor = theme.backgroundColor();
        this.backgroundImageUrl = theme.backgroundImageUrl();
        this.font = theme.font();
        this.corner = theme.corner();
        this.layout = theme.layout();
    }
}
