package com.example.sso.email.internal.domain;

import com.example.sso.email.template.EmailEvent;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tenant's override of ONE {@link EmailEvent}'s email — its own subject, HTML body, plain-text alternative
 * and logo URL. A {@code null} {@link #orgId} is the platform-wide default for that event; a non-null one is
 * that tenant's override. A tier with no row for an event inherits the platform row, else the built-in default.
 * The body is tenant-authored but rendered logic-less, so nothing here is executed.
 */
@Entity
@Table(name = "email_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailTemplate extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailEvent event;

    @Column(nullable = false)
    private String subject;

    @Column(name = "html_body", nullable = false, columnDefinition = "text")
    private String htmlBody;

    @Column(name = "text_body", columnDefinition = "text")
    private String textBody;

    @Column(name = "logo_url")
    private String logoUrl;

    /** Owning tenant, or {@code null} for the platform-wide default row. */
    public static EmailTemplate create(UUID orgId, EmailEvent event, String subject, String htmlBody,
            String textBody, String logoUrl) {
        EmailTemplate template = new EmailTemplate();
        template.orgId = orgId;
        template.event = event;
        template.apply(subject, htmlBody, textBody, logoUrl);
        return template;
    }

    /** Replace this template's content (intent-revealing mutation, not a JavaBean setter). */
    public void reconfigure(String subject, String htmlBody, String textBody, String logoUrl) {
        apply(subject, htmlBody, textBody, logoUrl);
    }

    private void apply(String subject, String htmlBody, String textBody, String logoUrl) {
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.textBody = textBody;
        this.logoUrl = logoUrl;
    }
}
