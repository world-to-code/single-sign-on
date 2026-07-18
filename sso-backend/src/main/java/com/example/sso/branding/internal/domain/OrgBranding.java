package com.example.sso.branding.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tenant's auth-UI branding override — its logo URL, accent color and product name. A {@code null}
 * {@link #orgId} is the platform-wide default; a non-null one is that tenant's override. A tier with no row
 * inherits the platform row, else the built-in default. Every field is optional (a surface keeps its own when
 * a value is absent). Public data — nothing here is a secret.
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

    @Column(name = "accent_color")
    private String accentColor;

    @Column(name = "product_name")
    private String productName;

    /** Owning tenant, or {@code null} for the platform-wide default row. */
    public static OrgBranding create(UUID orgId, String logoUrl, String accentColor, String productName) {
        OrgBranding branding = new OrgBranding();
        branding.orgId = orgId;
        branding.apply(logoUrl, accentColor, productName);
        return branding;
    }

    /** Replace this row's branding (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(String logoUrl, String accentColor, String productName) {
        apply(logoUrl, accentColor, productName);
    }

    private void apply(String logoUrl, String accentColor, String productName) {
        this.logoUrl = logoUrl;
        this.accentColor = accentColor;
        this.productName = productName;
    }
}
