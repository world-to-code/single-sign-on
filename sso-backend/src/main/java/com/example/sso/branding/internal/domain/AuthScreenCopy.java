package com.example.sso.branding.internal.domain;

import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.ScreenCopy;
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
 * One tier's wording for one sign-in screen. A {@code null} {@link #orgId} is the platform-wide default; a
 * non-null one is that tenant's override. Every field is optional and inherits independently, so a row may
 * carry a headline alone.
 */
@Entity
@Table(name = "auth_screen_copy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthScreenCopy extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "screen", nullable = false)
    private AuthScreen screen;

    @Column(name = "headline")
    private String headline;

    @Column(name = "subtext")
    private String subtext;

    @Column(name = "footer")
    private String footer;

    @Column(name = "help_url")
    private String helpUrl;

    /** Owning tenant, or {@code null} for the platform-wide default row. */
    public static AuthScreenCopy create(UUID orgId, AuthScreen screen, ScreenCopy copy) {
        AuthScreenCopy row = new AuthScreenCopy();
        row.orgId = orgId;
        row.screen = screen;
        row.apply(copy);
        return row;
    }

    /** Replace this row's wording (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(ScreenCopy copy) {
        apply(copy);
    }

    /** This row's wording. */
    public ScreenCopy copy() {
        return new ScreenCopy(headline, subtext, footer, helpUrl);
    }

    private void apply(ScreenCopy copy) {
        this.headline = copy.headline();
        this.subtext = copy.subtext();
        this.footer = copy.footer();
        this.helpUrl = copy.helpUrl();
    }
}
